package net.whago.annotrail;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewFrameTest {
    @TempDir Path temporary;

    @Test void rendersActualPdfWithBoundedDimensionsAndClosesItsInput() throws Exception {
        Path pdf = createPdf("한글 revised.pdf", new PDRectangle(2000, 4000));
        BufferedImage preview = ReviewFrame.renderPreview(pdf, 1, List.of());
        assertTrue(preview.getWidth() <= 1200 && preview.getHeight() <= 1200);
        assertEquals(1200, preview.getHeight()); assertEquals(600, preview.getWidth());
        Files.delete(pdf); assertFalse(Files.exists(pdf));
    }

    @Test void overlaysCandidateAtCorrectCropOffset() throws Exception {
        Path pdf = createPdf("crop.pdf", new PDRectangle(100, 200, 400, 400));
        BufferedImage plain = ReviewFrame.renderPreview(pdf, 1, List.of());
        BufferedImage marked = ReviewFrame.renderPreview(pdf, 1, List.of(140f, 500f, 260f, 500f, 140f, 480f, 260f, 480f));
        assertNotEquals(plain.getRGB(50, 110), marked.getRGB(50, 110));
        assertEquals(plain.getRGB(300, 300), marked.getRGB(300, 300));
    }

    @Test void invalidPageAndCancellationDoNotPretendToRender() throws Exception {
        Path pdf = createPdf("input.pdf", PDRectangle.A4);
        assertThrows(java.io.IOException.class, () -> ReviewFrame.renderPreview(pdf, 2, List.of()));
        try { Thread.currentThread().interrupt(); assertThrows(InterruptedIOException.class, () -> ReviewFrame.renderPreview(pdf, 1, List.of())); }
        finally { Thread.interrupted(); }
    }

    @Test void headlessReviewLayoutPaintsAndUntrustedTextCannotBecomeSwingHtml() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ReviewFrame panel = null;
            try {
                ReviewModel model = new ReviewModel();
                model.load(ReviewModelTest.plan(ReviewModelTest.entry("a", "confident", ReviewModelTest.candidate(2)), ReviewModelTest.entry("b", "ambiguous", ReviewModelTest.candidate(1), ReviewModelTest.candidate(3)), ReviewModelTest.entry("c", "removed"), ReviewModelTest.entry("d", "unsupported")));
                panel = new ReviewFrame(model); panel.setSize(1320, 940); layout(panel);
                JTable table = find(panel, JTable.class);
                assertEquals(4, table.getRowCount()); assertEquals("Not found", table.getValueAt(2, 1));
                JLabel renderer = (JLabel)table.getCellRenderer(0, 3).getTableCellRendererComponent(table, "<html><img src='https://invalid.test/a'>", false, false, 0, 3);
                assertEquals(Boolean.TRUE, renderer.getClientProperty("html.disable")); assertNull(renderer.getClientProperty("html"));
                assertTrue(findButton(panel, "Approve").isEnabled());
                findButton(panel, "Approve").doClick(); assertEquals(1, model.approvedCount());
                table.setRowSelectionInterval(1, 1); findButton(panel, "Skip").doClick(); assertEquals(1, model.skippedCount());
                BufferedImage image = new BufferedImage(1320, 940, BufferedImage.TYPE_INT_RGB); Graphics2D graphics = image.createGraphics();
                panel.printAll(graphics); graphics.dispose();
                Path out = Path.of(System.getProperty("annotrail.ui.screenshot", temporary.resolve("review-layout.png").toString()));
                Files.createDirectories(out.toAbsolutePath().getParent()); ImageIO.write(image, "png", out.toFile());
                assertTrue(Files.size(out) > 5000);
                panel.setSize(960, 720); layout(panel);
                for (String label : List.of("Approve", "Skip", "Reset")) {
                    JButton button = findButton(panel, label);
                    assertTrue(button.getX() + button.getWidth() <= button.getParent().getWidth(), label + " must fit the minimum window width");
                    assertTrue(button.getY() + button.getHeight() <= button.getParent().getHeight(), label + " must not wrap out of view");
                }
                assertNotNull(table.getTableHeader().getParent(), "The headless table must include its column headings");
            } catch (Throwable t) { failure.set(t); } finally { if (panel != null) panel.shutdown(); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private Path createPdf(String name, PDRectangle box) throws Exception {
        Path path = temporary.resolve(name);
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(box); document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                stream.newLineAtOffset(box.getLowerLeftX() + 40, box.getUpperRightY() - 50); stream.showText("A real PDF preview with selectable text."); stream.endText();
            }
            document.save(path.toFile());
        }
        return path;
    }
    private static void layout(Container c) { c.doLayout(); for (Component child : c.getComponents()) if (child instanceof Container container) layout(container); }
    private static <T> T find(Container c, Class<T> type) { for (Component child : c.getComponents()) { if (type.isInstance(child)) return type.cast(child); if (child instanceof Container nested) { T found = find(nested, type); if (found != null) return found; } } return null; }
    private static JButton findButton(Container c, String text) { for (Component child : c.getComponents()) { if (child instanceof JButton b && text.equals(b.getText())) return b; if (child instanceof Container nested) { JButton found = findButton(nested, text); if (found != null) return found; } } return null; }
}
