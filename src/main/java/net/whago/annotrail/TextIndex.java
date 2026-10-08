package net.whago.annotrail;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Text with a character-to-glyph map, in unrotated PDF user coordinates. */
final class TextIndex {
    record Glyph(float left, float bottom, float right, float top, int line) { }
    final String text;
    final List<Glyph> positions;
    final boolean rotated;

    private TextIndex(String text, List<Glyph> positions, boolean rotated) {
        this.text = text; this.positions = positions; this.rotated = rotated;
    }
    static void check(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Operation cancelled; no completed output was published.");
    }
    static TextIndex read(PDDocument doc, int page, int budget, BooleanSupplier cancelled) throws IOException {
        Reader reader = new Reader(doc.getPage(page), budget, cancelled);
        reader.setStartPage(page + 1); reader.setEndPage(page + 1); reader.setSortByPosition(true);
        reader.setSuppressDuplicateOverlappingText(true);
        reader.getText(doc);
        String raw = reader.raw.toString();
        StringBuilder normalized = new StringBuilder();
        List<Glyph> mapped = new ArrayList<>();
        BreakIterator clusters = BreakIterator.getCharacterInstance(Locale.ROOT);
        clusters.setText(raw);
        int begin = clusters.first();
        for (int end = clusters.next(); end != BreakIterator.DONE; begin = end, end = clusters.next()) {
            check(cancelled);
            String s = Normalizer.normalize(raw.substring(begin, end), Normalizer.Form.NFKC);
            Glyph glyph = reader.mapping.get(begin);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                    if (normalized.length() == 0 || normalized.charAt(normalized.length() - 1) == ' ') continue;
                    c = ' ';
                }
                normalized.append(c); mapped.add(glyph);
                if (normalized.length() > budget) throw new IOException("Selectable text exceeds the character limit.");
            }
        }
        if (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) == ' ') {
            normalized.setLength(normalized.length() - 1); mapped.remove(mapped.size() - 1);
        }
        return new TextIndex(normalized.toString(), mapped, reader.rotated);
    }
    String quote(float[] quads, BooleanSupplier cancelled) throws IOException {
        if (quads == null || quads.length == 0 || quads.length % 8 != 0) return "";
        if ((long)positions.size() * (quads.length / 8) > 20_000_000) return "";
        List<float[]> polygons = new ArrayList<>();
        for (int i=0;i<quads.length;i+=8) {
            float cx=0,cy=0;
            for(int n=0;n<4;n++){cx+=quads[i+2*n]/4;cy+=quads[i+2*n+1]/4;}
            final float centerX=cx,centerY=cy; final int offset=i;
            Integer[] order={0,1,2,3};
            Arrays.sort(order,Comparator.comparingDouble(n->Math.atan2(quads[offset+2*n+1]-centerY,quads[offset+2*n]-centerX)));
            float[] polygon=new float[8];
            for(int n=0;n<4;n++){polygon[2*n]=quads[i+2*order[n]];polygon[2*n+1]=quads[i+2*order[n]+1];}
            polygons.add(polygon);
        }
        StringBuilder result = new StringBuilder();
        boolean pendingSpace = false, pendingNonWhitespace = false;
        for (int i = 0; i < text.length(); i++) {
            check(cancelled);
            Glyph g = positions.get(i);
            if (g == null || !selected(g, polygons)) {
                pendingSpace = !result.isEmpty();
                if(!result.isEmpty() && text.charAt(i)!=' ') pendingNonWhitespace=true;
                continue;
            }
            // Disconnected highlight quads must not silently invent a new joined sentence.
            if(pendingNonWhitespace)return "";
            if (pendingSpace && !result.isEmpty() && result.charAt(result.length() - 1) != ' ' && text.charAt(i) != ' ')
                result.append(' ');
            result.append(text.charAt(i)); pendingSpace = false;
            if(result.length()>65_536) return "";
        }
        return result.toString().trim().replaceAll(" +", " ");
    }
    String nearby(PDRectangle rect) {
        if (rect == null || text.isEmpty()) return "";
        float x = rect.getLowerLeftX(), y = rect.getUpperRightY();
        Glyph nearest = null; double best = Double.MAX_VALUE;
        for (Glyph g : positions) {
            if (g == null) continue;
            double dx = Math.max(0, Math.max(g.left - x, x - g.right));
            double dy = Math.max(0, Math.max(g.bottom - y, y - g.top));
            double distance = dx * dx + dy * dy * 4;
            if (distance < best) { best = distance; nearest = g; }
        }
        // Do not associate a floating note with text on the other side of a page.
        if (nearest == null || best > 144 * 144) return "";
        int line = nearest.line; StringBuilder result = new StringBuilder(); boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            Glyph g = positions.get(i);
            if (g == null) { space = !result.isEmpty(); continue; }
            if (g.line == line) {
                if (space && !result.isEmpty() && result.charAt(result.length() - 1) != ' ') result.append(' ');
                result.append(text.charAt(i)); space = false;
            }
        }
        return result.toString().trim();
    }
    List<Float> quads(int start, int length) {
        List<Float> result = new ArrayList<>();
        Glyph group = null;
        for (int i = start; i < start + length; i++) {
            Glyph g = positions.get(i);
            if (g == null) continue;
            if (group == null) group = g;
            else if (g.line == group.line && Math.abs(g.bottom - group.bottom) < Math.max(2, g.top - g.bottom))
                group = new Glyph(Math.min(group.left,g.left), Math.min(group.bottom,g.bottom),
                                  Math.max(group.right,g.right), Math.max(group.top,g.top),group.line);
            else { appendQuad(result,group); group = g; }
        }
        if (group != null) appendQuad(result,group);
        return result;
    }
    private static void appendQuad(List<Float> out, Glyph g) {
        Collections.addAll(out,g.left,g.top,g.right,g.top,g.left,g.bottom,g.right,g.bottom);
    }
    private static boolean selected(Glyph g, List<float[]> polygons) {
        float x = (g.left + g.right) / 2, y = (g.top + g.bottom) / 2;
        for (float[] q : polygons) {
            boolean inside = false;
            for (int j = 0, k = 3; j < 4; k = j++) {
                float ax=q[2*j],ay=q[2*j+1],bx=q[2*k],by=q[2*k+1];
                if ((ay > y) != (by > y) && x < (bx-ax)*(y-ay)/(by-ay)+ax) inside=!inside;
            }
            if(inside)return true;
        }
        return false;
    }
    private static final class Reader extends PDFTextStripper {
        final StringBuilder raw = new StringBuilder(); final List<Glyph> mapping = new ArrayList<>();
        final PDRectangle crop; final int budget; final BooleanSupplier cancelled;
        int line = 0, processed = 0; boolean rotated;
        Reader(PDPage page, int budget, BooleanSupplier cancelled) throws IOException {
            crop = page.getCropBox(); this.budget = budget; this.cancelled = cancelled;
            rotated = page.getRotation() % 360 != 0;
        }
        @Override protected void processTextPosition(TextPosition p) {
            // PDFTextStripper's callback cannot throw a checked exception.
            if (++processed > budget || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new StopExtraction(processed > budget);
            super.processTextPosition(p);
        }
        @Override protected void writeString(String ignored, List<TextPosition> positions) throws IOException {
            check(cancelled);
            for (TextPosition p : positions) {
                if (Math.abs(p.getDir()) > 0.01 || Math.abs(p.getTextMatrix().getShearY()) > 0.01
                        || Math.abs(p.getTextMatrix().getShearX()) > 0.01) rotated = true;
                float x = crop.getLowerLeftX() + p.getXDirAdj();
                float baseline = crop.getUpperRightY() - p.getYDirAdj();
                if(!Float.isFinite(x) || !Float.isFinite(baseline) || !Float.isFinite(p.getWidthDirAdj()) || !Float.isFinite(p.getHeightDir())) continue;
                if(x < crop.getLowerLeftX()-0.01f || x+p.getWidthDirAdj() > crop.getUpperRightX()+0.01f
                    || baseline < crop.getLowerLeftY()-0.01f || baseline+p.getHeightDir() > crop.getUpperRightY()+0.01f) continue;
                Glyph glyph = new Glyph(x, baseline, x + p.getWidthDirAdj(), baseline + p.getHeightDir(), line);
                append(p.getUnicode(), glyph);
            }
        }
        @Override protected void writeWordSeparator() throws IOException { append(" ",null); }
        @Override protected void writeLineSeparator() throws IOException { append(" ",null); line++; }
        @Override protected void writeParagraphSeparator() throws IOException { append(" ",null); line++; }
        private void append(String s, Glyph g) throws IOException {
            if (s == null) return;
            if ((long)raw.length() + s.length() > budget) throw new IOException("Selectable text exceeds the character limit.");
            raw.append(s); for (int i = 0; i < s.length(); i++) mapping.add(g);
        }
    }
    static final class StopExtraction extends RuntimeException {
        final boolean limit;
        StopExtraction(boolean limit) { this.limit = limit; }
    }
}
