package net.whago.annotrail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Publishes a small batch without replacing existing paths, rolling it back until committed.
 * Hard links expose a complete file atomically. The exclusive-copy fallback exposes a partial
 * file while copying, and process crashes can leave it behind. Identity checks protect ordinary
 * concurrent replacements; portable Java does not provide an atomic compare-and-unlink operation.
 * A provider without file keys cannot distinguish a later replacement with identical contents.
 */
final class OutputPublication implements AutoCloseable {
    /** Package-private seam for exercising filesystem failures without requiring an external disk. */
    static class FileOperations {
        void createLink(Path output, Path staged) throws IOException { Files.createLink(output,staged); }
        SeekableByteChannel createNew(Path output) throws IOException {
            return Files.newByteChannel(output,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);
        }
        BasicFileAttributes attributes(Path path) throws IOException {
            return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        }
    }

    private final FileOperations files;
    private final BooleanSupplier cancelled;
    private final List<OwnedFile> published = new ArrayList<>();
    private boolean committed;

    OutputPublication(FileOperations files, BooleanSupplier cancelled) {
        this.files=Objects.requireNonNull(files); this.cancelled=Objects.requireNonNull(cancelled);
    }

    void publish(Path staged, Path output) throws IOException {
        if (committed) throw new IllegalStateException("Publication was already committed.");
        TextIndex.check(cancelled);
        try {
            files.createLink(output,staged);
            published.add(new OwnedFile(output,staged,null));
            return;
        } catch (FileAlreadyExistsException collision) {
            throw collision;
        } catch (IOException | UnsupportedOperationException linkFailure) {
            // Providers report unsupported hard links differently. CREATE_NEW remains exclusive
            // even for access failures, collisions reported as generic IOExceptions, or races.
            try {
                copyExclusive(staged,output);
            } catch (IOException | RuntimeException copyFailure) {
                copyFailure.addSuppressed(linkFailure);
                throw copyFailure;
            }
        }
    }

    private void copyExclusive(Path staged, Path output) throws IOException {
        TextIndex.check(cancelled);
        SeekableByteChannel target=files.createNew(output);
        OwnedFile owned=new OwnedFile(output,null,target);
        published.add(owned); // Track the reservation before any subsequent operation can fail.
        owned.identity=files.attributes(output);
        // Bind the observed path to our CREATE_NEW handle before copying. A random reservation
        // marker detects replacement even if attributes were first read from the replacement.
        byte[] marker=UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ByteBuffer reservation=ByteBuffer.wrap(marker);
        while(reservation.hasRemaining())target.write(reservation);
        try(var reserved=Files.newInputStream(output,LinkOption.NOFOLLOW_LINKS)) {
            if(!Arrays.equals(marker,reserved.readNBytes(marker.length+1)))
                throw new IOException("Output changed during reservation; preserved replacement: "+output);
        }
        if(!owned.sameIdentity(files.attributes(output)))
            throw new IOException("Output changed during reservation; preserved replacement: "+output);
        target.position(0);target.truncate(0);
        try (SeekableByteChannel source=Files.newByteChannel(staged,StandardOpenOption.READ)) {
            ByteBuffer buffer=ByteBuffer.allocate(64*1024);
            while (true) {
                TextIndex.check(cancelled);
                int read=source.read(buffer);
                if (read<0) break;
                buffer.flip();
                while (buffer.hasRemaining()) {
                    TextIndex.check(cancelled);
                    target.write(buffer);
                }
                buffer.clear();
            }
            TextIndex.check(cancelled);
        }
    }

    void commit() throws IOException {
        // Close before committing: delayed write/close errors must still roll the batch back.
        TextIndex.check(cancelled);
        IOException failure=null;
        for (OwnedFile file:published) {
            try { file.closeChannel(); } catch (IOException close) { failure=combine(failure,close); }
        }
        if (failure!=null) throw failure;
        for(OwnedFile file:published) {
            TextIndex.check(cancelled);
            if(!file.stillOwned())throw new IOException("Output changed before completion; preserved replacement: "+file.path);
        }
        TextIndex.check(cancelled);
        committed=true;
    }

    @Override public void close() throws IOException {
        if (committed) return;
        IOException failure=null;
        for (int i=published.size()-1;i>=0;i--) {
            try { published.get(i).rollback(); } catch (IOException cleanup) { failure=combine(failure,cleanup); }
        }
        published.clear();
        if (failure!=null) throw new RollbackException("Output cleanup could not be completed safely: "+failure.getMessage(),failure);
    }

    static final class RollbackException extends IOException {
        RollbackException(String message,Throwable cause){super(message,cause);}
    }

    private final class OwnedFile {
        final Path path, staged;
        final SeekableByteChannel channel;
        BasicFileAttributes identity;
        Fingerprint fingerprint;
        OwnedFile(Path path,Path staged,SeekableByteChannel channel) {
            this.path=path; this.staged=staged; this.channel=channel;
        }
        void closeChannel() throws IOException {
            if (channel==null || !channel.isOpen()) return;
            IOException failure=null;
            // Capture through our own handle on every provider: even the initial pathname
            // identity read could have observed a replacement. A key alone cannot prove ownership.
            try { fingerprint=fingerprint(channel); }
            catch (IOException read) { failure=read; }
            try { channel.close(); } catch (IOException close) { failure=combine(failure,close); }
            if (failure!=null) throw failure;
        }
        void rollback() throws IOException {
            IOException closeFailure=null;
            try { closeChannel(); } catch (IOException close) { closeFailure=close; }
            try {
                if (stillOwned()) Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                if (closeFailure!=null) cleanup.addSuppressed(closeFailure);
                throw cleanup;
            }
            if (closeFailure!=null) throw closeFailure;
        }
        boolean sameIdentity(BasicFileAttributes current) {
            // Some providers emulate creationTime with lastModifiedTime, which our own writes
            // change. Use the stable key when present; a missing key additionally relies on the
            // attested reservation and handle/path content comparison, not an unstable timestamp.
            return identity!=null && current.isRegularFile()
                && Objects.equals(identity.fileKey(),current.fileKey());
        }
        boolean stillOwned() throws IOException {
            BasicFileAttributes current;
            try { current=files.attributes(path); } catch (NoSuchFileException missing) { return false; }
            // Never follow a replacement symlink or remove a replacement directory/file.
            if (!current.isRegularFile()) return false;
            if (staged!=null) return Files.isSameFile(path,staged);
            if (identity==null || fingerprint==null)
                throw new IOException("Cannot verify ownership; preserved output: "+path);
            if(!sameIdentity(current) || current.size()!=fingerprint.bytes())return false;
            try (SeekableByteChannel existing=Files.newByteChannel(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                return fingerprint.equals(fingerprint(existing));
            }
        }
    }

    private record Fingerprint(long bytes,String sha256) { }
    private static Fingerprint fingerprint(SeekableByteChannel channel) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            channel.position(0); ByteBuffer buffer=ByteBuffer.allocate(64*1024); long bytes=0;
            while (true) {
                int count=channel.read(buffer); if (count<0) break;
                bytes+=count; buffer.flip(); digest.update(buffer); buffer.clear();
            }
            return new Fingerprint(bytes,HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static IOException combine(IOException first,IOException next) {
        if (first==null) return next;
        first.addSuppressed(next); return first;
    }
}
