package net.whago.annotrail;

import static org.junit.jupiter.api.Assertions.*;
import static net.whago.annotrail.Models.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real embedded Type0 text, independent of the machine's installed fonts. */
class UnicodePdfTest {
    private static final String FONT = "/fonts/AnnotrailTestCJK-Regular.ttf";
    private static final String KOREAN = "개정 문서에서도 한글 인용문과 수치 42를 정확하게 보존합니다.";
    private static final String JAPANESE = "改訂後も日本語の引用文と数値42を正確に保持します。";
    private static final String CHINESE = "修订文档仍然保留中文引用和数值42的准确内容。";
    private static final String COMMENT = "수치 42와 인용문을 확인했습니다.";
    private static final String AUTHOR = "가상 검토자";
    private static final float SIZE = 12;
    private static final float[] COLOR = {0.2f, 0.7f, 0.9f};

    @TempDir Path directory;

    @Test void koreanSelectableTextMovesAndMetadataSurvivesReopenedExport() throws Exception {
        assertRebased(KOREAN, KOREAN, KOREAN);
    }

    @Test void japaneseSelectableTextMovesWithoutWhitespaceWordBoundaries() throws Exception {
        assertRebased(JAPANESE, JAPANESE, JAPANESE);
    }

    @Test void chineseSelectableTextMovesWithoutWhitespaceWordBoundaries() throws Exception {
        assertRebased(CHINESE, CHINESE, CHINESE);
    }

    @Test void sourceLigaturesExpandToPlainTextWithoutLosingGlyphCoordinates() throws Exception {
        assertRebased("The ﬁnal ﬁnancial ﬁgure is exactly forty two.",
            "The final financial figure is exactly forty two.",
            "The final financial figure is exactly forty two.");
    }

    @Test void targetLigaturesAcceptExpandedQuoteWithoutLosingGlyphCoordinates() throws Exception {
        assertRebased("The final financial figure is exactly forty two.",
            "The ﬁnal ﬁnancial ﬁgure is exactly forty two.",
            "The final financial figure is exactly forty two.");
    }

    @Test void duplicateKoreanQuotesRemainAmbiguousAndRequireAnExplicitChoice() throws Exception {
        Path source = directory.resolve("원본.pdf");
        Path revised = directory.resolve("수정본.pdf");
        makeSource(source, KOREAN);
        makeRevised(revised, KOREAN, true);
        Plan plan = Rebaser.analyze(source, revised, Options.defaults(), () -> false);
        Entry entry = plan.entries().get(0);
        assertEquals(KOREAN, entry.quote());
        assertEquals("ambiguous", entry.status());
        assertEquals(2, entry.candidates().size());
        assertThrows(IOException.class, () -> Rebaser.export(source, revised, plan, Map.of(),
            directory.resolve("결과.pdf"), directory.resolve("검토.json"), () -> false));
        assertFalse(Files.exists(directory.resolve("결과.pdf")));
    }

    private void assertRebased(String oldText, String newText, String normalized) throws Exception {
        Path source = directory.resolve("원본.pdf");
        Path revised = directory.resolve("수정본.pdf");
        Path output = directory.resolve("결과.pdf");
        Path report = directory.resolve("검토.json");
        makeSource(source, oldText);
        makeRevised(revised, newText, false);
        byte[] originalBytes = Files.readAllBytes(source);
        byte[] revisedBytes = Files.readAllBytes(revised);

        Plan plan = Rebaser.analyze(source, revised, Options.defaults(), () -> false);
        assertEquals(1, plan.entries().size());
        Entry entry = plan.entries().get(0);
        assertEquals(normalized, entry.quote());
        assertEquals("confident", entry.status());
        assertEquals(1, entry.candidates().size());
        assertEquals(2, entry.candidates().get(0).page());
        assertEquals(COMMENT, entry.comment());
        assertEquals(AUTHOR, entry.author());
        assertEquals("원본.pdf", plan.original().name());
        Result result = Rebaser.export(source, revised, plan, Map.of(entry.id(), 0),
            output, report, () -> false);
        assertEquals(1, result.transferred());
        assertEquals(0, result.skipped());

        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertTrue(document.getPage(0).getAnnotations().isEmpty());
            assertEquals(1, document.getPage(1).getAnnotations().size());
            PDAnnotationHighlight mark = assertInstanceOf(PDAnnotationHighlight.class,
                document.getPage(1).getAnnotations().get(0));
            assertEquals(COMMENT, mark.getContents());
            assertEquals(AUTHOR, mark.getTitlePopup());
            assertArrayEquals(COLOR, mark.getColor().getComponents(), 0.001f);
            assertEquals(0.65f, mark.getConstantOpacity(), 0.001f);
            assertNotNull(mark.getAppearance());
            float[] quad = mark.getQuadPoints();
            assertEquals(8, quad.length);
            assertEquals(80, quad[0], 0.1f);
            assertEquals(580, quad[5], 0.1f);
            assertTrue(quad[1] > quad[5]);
            assertTrue(quad[2] > quad[0]);
            // Selection from the saved geometry must still recover the entire normalized quote.
            TextIndex selected = TextIndex.read(document, 1, 100_000, () -> false);
            assertEquals(normalized, selected.quote(quad, () -> false));
        }
        assertArrayEquals(originalBytes, Files.readAllBytes(source));
        assertArrayEquals(revisedBytes, Files.readAllBytes(revised));
        String json = Files.readString(report);
        assertTrue(json.contains(normalized));
        assertTrue(json.contains(COMMENT));
        assertTrue(json.contains(AUTHOR));
        assertFalse(json.contains(directory.toString()));
    }

    private static PDType0Font font(PDDocument document) throws IOException {
        try (InputStream stream = UnicodePdfTest.class.getResourceAsStream(FONT)) {
            if (stream == null) throw new IOException("Missing licensed Unicode fixture font.");
            return PDType0Font.load(document, stream, true);
        }
    }

    private static void makeSource(Path path, String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDType0Font font = font(document);
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            line(document, page, font, text, 50, 700);
            float width = font.getStringWidth(text) * SIZE / 1000;
            PDAnnotationTextMarkup mark = new PDAnnotationHighlight();
            mark.setQuadPoints(new float[] {50, 714, 50 + width, 714, 50, 698, 50 + width, 698});
            mark.setRectangle(new PDRectangle(50, 698, width, 16));
            mark.setContents(COMMENT);
            mark.setTitlePopup(AUTHOR);
            mark.setColor(new PDColor(COLOR, PDDeviceRGB.INSTANCE));
            mark.setConstantOpacity(0.65f);
            page.getAnnotations().add(mark);
            document.save(path.toFile());
        }
    }

    private static void makeRevised(Path path, String text, boolean duplicate) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDType0Font font = font(document);
            PDPage preface = new PDPage(PDRectangle.LETTER);
            document.addPage(preface);
            line(document, preface, font, "A new preface moves the cited passage to page two.", 50, 720);
            PDPage destination = new PDPage(PDRectangle.LETTER);
            document.addPage(destination);
            line(document, destination, font, text, 80, 580);
            if (duplicate) line(document, destination, font, text, 80, 480);
            document.save(path.toFile());
        }
    }

    private static void line(PDDocument document, PDPage page, PDType0Font font,
                             String text, float x, float y) throws IOException {
        try (PDPageContentStream content = new PDPageContentStream(document, page,
                PDPageContentStream.AppendMode.APPEND, true)) {
            content.beginText();
            content.setFont(font, SIZE);
            content.newLineAtOffset(x, y);
            content.showText(text);
            content.endText();
        }
    }
}
