package net.whago.annotrail;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputPublicationTest {
    @TempDir Path dir;

    private Path staged() throws IOException {
        byte[] bytes=new byte[196_613]; new Random(741).nextBytes(bytes);
        return Files.write(dir.resolve("staged"),bytes);
    }
    private static class NoLinks extends OutputPublication.FileOperations {
        @Override void createLink(Path output,Path staged) throws IOException {
            throw new FileSystemException(output.toString(),staged.toString(),"Operation not supported");
        }
    }
    private static class NoKeys extends NoLinks {
        @Override BasicFileAttributes attributes(Path path) throws IOException {
            BasicFileAttributes real=super.attributes(path);
            return new BasicFileAttributes() {
                public FileTime lastModifiedTime(){return real.lastModifiedTime();}
                public FileTime lastAccessTime(){return real.lastAccessTime();}
                public FileTime creationTime(){return real.creationTime();}
                public boolean isRegularFile(){return real.isRegularFile();}
                public boolean isDirectory(){return real.isDirectory();}
                public boolean isSymbolicLink(){return real.isSymbolicLink();}
                public boolean isOther(){return real.isOther();}
                public long size(){return real.size();}
                public Object fileKey(){return null;}
            };
        }
    }
    private static class ForwardingChannel implements SeekableByteChannel {
        final SeekableByteChannel delegate;
        ForwardingChannel(SeekableByteChannel delegate){this.delegate=delegate;}
        public int read(ByteBuffer dst)throws IOException{return delegate.read(dst);}
        public int write(ByteBuffer src)throws IOException{return delegate.write(src);}
        public long position()throws IOException{return delegate.position();}
        public SeekableByteChannel position(long position)throws IOException{delegate.position(position);return this;}
        public long size()throws IOException{return delegate.size();}
        public SeekableByteChannel truncate(long size)throws IOException{delegate.truncate(size);return this;}
        public boolean isOpen(){return delegate.isOpen();}
        public void close()throws IOException{delegate.close();}
    }
    @Test void hardLinkFastPathDoesNotOpenACopyAndCommitKeepsOutput()throws Exception {
        Path stage=staged(),out=dir.resolve("out");
        var files=new OutputPublication.FileOperations(){
            @Override SeekableByteChannel createNew(Path output){throw new AssertionError("Copy should not be needed");}
        };
        try(var publication=new OutputPublication(files,()->false)) {
            publication.publish(stage,out);assertTrue(Files.isSameFile(stage,out));publication.commit();
        }
        assertArrayEquals(Files.readAllBytes(stage),Files.readAllBytes(out));
    }
    @Test void unsupportedLinkCopiesExclusivelyAndCommitsAllBytes()throws Exception {
        Path stage=staged(),out=dir.resolve("out");
        try(var publication=new OutputPublication(new NoLinks(),()->false)) {
            publication.publish(stage,out);assertFalse(Files.isSameFile(stage,out));publication.commit();
        }
        assertArrayEquals(Files.readAllBytes(stage),Files.readAllBytes(out));
    }
    @Test void providerUnsupportedOperationExceptionAlsoUsesCopy()throws Exception {
        Path stage=staged(),out=dir.resolve("out");
        var files=new OutputPublication.FileOperations(){
            @Override void createLink(Path output,Path staged){throw new UnsupportedOperationException("No hard links");}
        };
        try(var publication=new OutputPublication(files,()->false)) {publication.publish(stage,out);publication.commit();}
        assertArrayEquals(Files.readAllBytes(stage),Files.readAllBytes(out));
    }
    @Test void collisionsNeverOverwriteForEitherPublicationStrategy()throws Exception {
        Path stage=staged();
        for(var files:List.of(new OutputPublication.FileOperations(),new NoLinks())) {
            Path out=dir.resolve("existing");Files.writeString(out,"unrelated existing data");
            try(var publication=new OutputPublication(files,()->false)) {
                assertThrows(FileAlreadyExistsException.class,()->publication.publish(stage,out));
            }
            assertEquals("unrelated existing data",Files.readString(out));Files.delete(out);
        }
    }
    @Test void cancellationDuringCopyCleansPartialReservation()throws Exception {
        Path stage=staged(),out=dir.resolve("out");AtomicBoolean cancelled=new AtomicBoolean();
        var files=new NoLinks(){
            @Override SeekableByteChannel createNew(Path path)throws IOException {
                return new ForwardingChannel(super.createNew(path)){
                    int writes;
                    @Override public int write(ByteBuffer source)throws IOException {
                        int count=super.write(source);if(++writes>1)cancelled.set(true);return count;
                    }
                };
            }
        };
        assertThrows(InterruptedIOException.class,()->{
            try(var publication=new OutputPublication(files,cancelled::get)){publication.publish(stage,out);publication.commit();}
        });
        assertFalse(Files.exists(out));assertEquals(196_613,Files.size(stage));
    }
    @Test void partialWriteThenFailureCleansOwnedFileEvenWithoutFileKey()throws Exception {
        Path stage=staged(),out=dir.resolve("out");
        var files=new NoKeys(){
            @Override SeekableByteChannel createNew(Path path)throws IOException {
                return new ForwardingChannel(super.createNew(path)){
                    int writes;
                    @Override public int write(ByteBuffer source)throws IOException {
                        if(++writes==1)return super.write(source);
                        int limit=source.limit();source.limit(source.position()+31);
                        try{super.write(source);}finally{source.limit(limit);}
                        throw new IOException("Simulated disk full after 31 bytes");
                    }
                };
            }
        };
        IOException failure=assertThrows(IOException.class,()->{
            try(var publication=new OutputPublication(files,()->false)){publication.publish(stage,out);publication.commit();}
        });
        assertTrue(failure.getMessage().contains("disk full"));assertFalse(Files.exists(out));
    }
    @Test void delayedCloseFailureRollsBackAllPublishedFiles()throws Exception {
        Path stage=staged(),first=dir.resolve("first"),second=dir.resolve("second");
        var files=new NoLinks(){
            @Override SeekableByteChannel createNew(Path path)throws IOException {
                return new ForwardingChannel(super.createNew(path)){
                    @Override public void close()throws IOException {
                        boolean wasOpen=isOpen();super.close();
                        if(wasOpen && path.equals(second))throw new IOException("Simulated delayed write failure");
                    }
                };
            }
        };
        assertThrows(IOException.class,()->{
            try(var publication=new OutputPublication(files,()->false)) {
                publication.publish(stage,first);publication.publish(stage,second);publication.commit();
            }
        });
        assertFalse(Files.exists(first));assertFalse(Files.exists(second));
    }
    @Test void failedSecondReservationRollsBackFirstButPreservesCollision()throws Exception {
        Path stage=staged(),first=dir.resolve("first"),second=dir.resolve("second");
        assertThrows(IOException.class,()->{
            try(var publication=new OutputPublication(new NoLinks(),()->false)) {
                publication.publish(stage,first);Files.writeString(second,"someone else's report");
                publication.publish(stage,second);publication.commit();
            }
        });
        assertFalse(Files.exists(first));assertEquals("someone else's report",Files.readString(second));
    }
    @Test void rollbackKeepsReplacementFileForBothStrategies()throws Exception {
        Path stage=staged();
        for(var files:List.of(new OutputPublication.FileOperations(),new NoLinks())) {
            Path out=dir.resolve("out");
            try(var publication=new OutputPublication(files,()->false)) {
                publication.publish(stage,out);
                // Unix permits replacing an open file. Windows may deny this, itself preserving ownership.
                try { Files.delete(out); }
                catch(AccessDeniedException locked){continue;}
                Files.writeString(out,"replacement data",StandardOpenOption.CREATE_NEW);
            }
            assertTrue(Files.exists(out),"Rollback must preserve a replacement file");
            assertEquals("replacement data",Files.readString(out));Files.delete(out);
        }
    }
    @Test void fallbackWithoutFileKeyUsesOwnedContentToRecognizeChanges()throws Exception {
        Path stage=staged(),out=dir.resolve("out");
        try(var publication=new OutputPublication(new NoKeys(),()->false)) {
            publication.publish(stage,out);
            try { Files.delete(out); }
            catch(AccessDeniedException locked){return;}
            Files.writeString(out,"different file",StandardOpenOption.CREATE_NEW);
        }
        assertEquals("different file",Files.readString(out));
    }
    @Test void fallbackWithoutFileKeyRollsBackUncommittedCopy()throws Exception {
        Path stage=staged(),out=dir.resolve("out");
        try(var publication=new OutputPublication(new NoKeys(),()->false)){publication.publish(stage,out);}
        assertFalse(Files.exists(out));assertEquals(196_613,Files.size(stage));
    }
    @Test void alreadyCancelledNeverReservesAPath()throws Exception {
        Path stage=staged(),out=dir.resolve("out");
        try(var publication=new OutputPublication(new NoLinks(),()->true)) {
            assertThrows(InterruptedIOException.class,()->publication.publish(stage,out));
        }
        assertFalse(Files.exists(out));
    }
    @Test void replacementBetweenReservationAndIdentityCaptureIsNeverDeleted()throws Exception {
        Path stage=staged(),out=dir.resolve("out"),moved=dir.resolve("our-unlinked-file");
        var files=new NoLinks(){
            boolean replaced;
            @Override BasicFileAttributes attributes(Path path)throws IOException {
                if(path.equals(out) && !replaced) {
                    replaced=true;
                    Files.move(out,moved);
                    Files.writeString(out,"replacement before identity capture",StandardOpenOption.CREATE_NEW);
                }
                return super.attributes(path);
            }
        };
        IOException failure=assertThrows(IOException.class,()->{
            try(var publication=new OutputPublication(files,()->false)){publication.publish(stage,out);}
        });
        assertTrue(failure.getMessage().contains("changed during reservation"));
        assertEquals("replacement before identity capture",Files.readString(out));
        assertTrue(Files.size(moved)>0);
    }
    @Test void unidentifiedOutputIsPreservedWithExplicitCleanupFailure()throws Exception {
        Path stage=staged(),out=dir.resolve("out");
        var files=new NoLinks(){
            boolean failed;
            @Override BasicFileAttributes attributes(Path path)throws IOException {
                if(!failed){failed=true;throw new IOException("Simulated attribute read failure");}
                return super.attributes(path);
            }
        };
        IOException failure=assertThrows(IOException.class,()->{
            try(var publication=new OutputPublication(files,()->false)){publication.publish(stage,out);}
        });
        assertTrue(Files.exists(out));assertEquals(0,Files.size(out));
        assertTrue(Arrays.stream(failure.getSuppressed()).anyMatch(e->e instanceof OutputPublication.RollbackException
            && e.getMessage().contains("preserved output")));
    }

    @Test void preexistingSymlinkIsNeverFollowedOrRemoved()throws Exception {
        Path stage=staged(),out=dir.resolve("out"),victim=dir.resolve("victim");
        Files.writeString(victim,"unrelated data");
        for(var files:List.of(new OutputPublication.FileOperations(),new NoLinks())) {
            try{Files.createSymbolicLink(out,victim);}
            catch(UnsupportedOperationException | FileSystemException unavailable){return;}
            try(var publication=new OutputPublication(files,()->false)) {
                assertThrows(FileAlreadyExistsException.class,()->publication.publish(stage,out));
            }
            assertTrue(Files.isSymbolicLink(out));assertEquals("unrelated data",Files.readString(victim));Files.delete(out);
        }
    }
    @Test void replacementSymlinkIsNeverRemovedEvenWhenItPointsToOurStagedFile()throws Exception {
        Path stage=staged(),out=dir.resolve("out"),probe=dir.resolve("probe");
        try{Files.createSymbolicLink(probe,stage);Files.delete(probe);}
        catch(UnsupportedOperationException | FileSystemException unavailable){return;}
        for(var files:List.of(new OutputPublication.FileOperations(),new NoLinks())) {
            try(var publication=new OutputPublication(files,()->false)) {
                publication.publish(stage,out);
                try{Files.delete(out);}catch(AccessDeniedException locked){continue;}
                Files.createSymbolicLink(out,stage);
            }
            assertTrue(Files.isSymbolicLink(out));assertEquals(196_613,Files.size(stage));Files.delete(out);
        }
    }
    @Test void replacementIsDetectedBeforeSuccessfulCommit()throws Exception {
        Path stage=staged(),out=dir.resolve("out");
        try(var publication=new OutputPublication(new NoLinks(),()->false)) {
            publication.publish(stage,out);
            try{Files.delete(out);}catch(AccessDeniedException locked){return;}
            Files.writeString(out,"external replacement",StandardOpenOption.CREATE_NEW);
            IOException failure=assertThrows(IOException.class,publication::commit);
            assertTrue(failure.getMessage().contains("changed before completion"));
        }
        assertEquals("external replacement",Files.readString(out));
    }

    @Test void emulatedCreationTimeMayChangeDuringOurWrites()throws Exception {
        Path stage=staged();
        for(boolean omitKey:List.of(false,true)) {
            var files=new NoLinks(){
                @Override BasicFileAttributes attributes(Path path)throws IOException {
                    BasicFileAttributes real=super.attributes(path);
                    return new BasicFileAttributes(){
                        public FileTime lastModifiedTime(){return real.lastModifiedTime();}
                        public FileTime lastAccessTime(){return real.lastAccessTime();}
                        public FileTime creationTime(){return real.lastModifiedTime();}
                        public boolean isRegularFile(){return real.isRegularFile();}
                        public boolean isDirectory(){return real.isDirectory();}
                        public boolean isSymbolicLink(){return real.isSymbolicLink();}
                        public boolean isOther(){return real.isOther();}
                        public long size(){return real.size();}
                        public Object fileKey(){return omitKey?null:real.fileKey();}
                    };
                }
                @Override SeekableByteChannel createNew(Path path)throws IOException {
                    return new ForwardingChannel(super.createNew(path)){
                        int writes;
                        @Override public int write(ByteBuffer source)throws IOException {
                            int count=super.write(source);
                            Files.setLastModifiedTime(path,FileTime.fromMillis(4_000L+2_000L*++writes));return count;
                        }
                    };
                }
            };
            Path committed=dir.resolve("committed-"+omitKey),rolledBack=dir.resolve("rolled-back-"+omitKey);
            try(var publication=new OutputPublication(files,()->false)){publication.publish(stage,committed);publication.commit();}
            assertArrayEquals(Files.readAllBytes(stage),Files.readAllBytes(committed));
            try(var publication=new OutputPublication(files,()->false)){publication.publish(stage,rolledBack);}
            assertFalse(Files.exists(rolledBack));
        }
    }

}
