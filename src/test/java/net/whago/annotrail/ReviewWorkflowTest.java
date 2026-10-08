package net.whago.annotrail;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real Swing buttons and worker/services, offscreen; does not exercise native file choosers. */
class ReviewWorkflowTest {
    @TempDir Path directory;
    record Notice(String title, String body, int type) { }

    @Test void realPdfAnalysisReviewPreviewAndExportShareTheDesktopWorkflow() throws Exception {
        FixtureGenerator.generate(directory);
        Path original = directory.resolve("old.pdf"), revised = directory.resolve("new.pdf");
        byte[] originalBytes = Files.readAllBytes(original), revisedBytes = Files.readAllBytes(revised);
        Path output = directory.resolve("reviewed.pdf"), report = directory.resolve("reviewed.json");
        ReviewModel model = new ReviewModel(); List<Notice> notices = new CopyOnWriteArrayList<>();
        ReviewFrame frame = edt(() -> new ReviewFrame(model, (t, m, k) -> notices.add(new Notice(t, m, k))));
        try {
            edt(() -> {
                frame.setSize(1320, 940); layout(frame); frame.selectInputs(original, revised);
                assertTrue(button(frame, "Analyze PDFs").isEnabled());
                button(frame, "Analyze PDFs").doClick(0);
                assertTrue(frame.isWorking()); assertFalse(button(frame, "Analyze PDFs").isEnabled());
                assertFalse(button(frame, "Choose original…").isEnabled());
                assertThrows(IllegalStateException.class, () -> frame.selectInputs(original, revised));
                return null;
            });
            await(() -> !frame.isWorking());
            edt(() -> {
                assertNotNull(model.plan(), notices.toString());
                JTable table = component(frame, JTable.class);
                assertEquals(4, table.getRowCount()); assertEquals(4, model.unreviewedCount());
                assertEquals(List.of("Unique exact text", "Needs review", "Not found", "Unsupported"),
                        java.util.stream.IntStream.range(0, 4).mapToObj(i -> table.getValueAt(i, 1)).toList());
                assertFalse(button(frame, "Export reviewed PDF…").isEnabled());
                assertThrows(IllegalStateException.class, () -> frame.exportTo(output, report));
                table.setRowSelectionInterval(1, 1);
                JComboBox<?> candidates = component(frame, JComboBox.class);
                assertEquals(2, candidates.getItemCount()); candidates.setSelectedIndex(1);
                button(frame, "Approve").doClick(0); assertEquals(1, model.choice(model.entries().get(1)));
                table.setRowSelectionInterval(0, 0); button(frame, "Approve").doClick(0);
                table.setRowSelectionInterval(2, 2); assertFalse(button(frame, "Approve").isEnabled()); button(frame, "Skip").doClick(0);
                table.setRowSelectionInterval(3, 3); button(frame, "Skip").doClick(0);
                assertTrue(button(frame, "Export reviewed PDF…").isEnabled());
                assertEquals(2, model.approvedCount()); assertEquals(2, model.skippedCount());
                table.setRowSelectionInterval(1, 1); assertEquals(1, candidates.getSelectedIndex());
                return null;
            });
            await(() -> preview(frame, "Original annotation preview").getIcon() != null && preview(frame, "Revised destination preview").getIcon() != null);
            edt(() -> {
                assertTrue(((ImageIcon) preview(frame, "Original annotation preview").getIcon()).getIconWidth() > 100);
                assertTrue(((ImageIcon) preview(frame, "Revised destination preview").getIcon()).getIconHeight() > 100);
                layout(frame);
                for (String text : List.of("Page 1 · Underline · Needs review", "Approved — destination 2", "2 approved   ·   2 skipped   ·   0 unreviewed", "1 unique · 1 review · 1 not found · 1 unsupported")) {
                    JLabel label = label(frame, text); assertNotNull(label, text);
                    assertTrue(label.getWidth() >= label.getPreferredSize().width, "Dynamic label must not retain its initial narrow width: " + text);
                }
                BufferedImage image = new BufferedImage(1320, 940, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics(); frame.printAll(graphics); graphics.dispose();
                Path screenshot = Path.of(System.getProperty("annotrail.workflow.screenshot", directory.resolve("workflow.png").toString()));
                Files.createDirectories(screenshot.toAbsolutePath().getParent()); ImageIO.write(image, "png", screenshot.toFile());
                frame.exportTo(output, report); assertTrue(frame.isWorking());
                assertFalse(button(frame, "Approve").isEnabled()); return null;
            });
            await(() -> !frame.isWorking());
            assertTrue(Files.isRegularFile(output), notices.toString()); assertTrue(Files.isRegularFile(report), notices.toString());
            assertTrue(notices.stream().anyMatch(n -> n.title().equals("Export complete")), notices.toString());
            assertTrue(notices.stream().noneMatch(n -> n.type() == JOptionPane.ERROR_MESSAGE), notices.toString());
            try (PDDocument actual = Loader.loadPDF(output.toFile())) {
                assertEquals(2, actual.getNumberOfPages()); assertEquals(1, actual.getPage(0).getAnnotations().size());
                assertEquals("Existing revised annotation.", actual.getPage(0).getAnnotations().get(0).getContents());
                assertEquals(2, actual.getPage(1).getAnnotations().size());
                PDAnnotationTextMarkup selected = (PDAnnotationTextMarkup)actual.getPage(1).getAnnotations().get(1);
                assertEquals("Choose the correct repeated section.", selected.getContents());
                assertEquals(model.entries().get(1).candidates().get(1).quads().get(1), selected.getQuadPoints()[1], 0.01);
            }
            var json = JsonParser.parseString(Files.readString(report)).getAsJsonObject();
            assertEquals(2, json.getAsJsonObject("result").get("transferred").getAsInt());
            assertEquals(2, json.getAsJsonObject("result").get("skipped").getAsInt());
            assertEquals(1, json.getAsJsonObject("choices").get(model.entries().get(1).id()).getAsInt());
            assertTrue(Files.readString(report).contains("Retain this unresolved note in the report."));
            assertArrayEquals(originalBytes, Files.readAllBytes(original)); assertArrayEquals(revisedBytes, Files.readAllBytes(revised));
        } finally { edt(() -> { frame.shutdown(); return null; }); await(frame::isStopped); }
    }

    @Test void cancelAndMalformedInputRecoverWithoutFalseCompletionOrStaleReview() throws Exception {
        FixtureGenerator.generate(directory);
        Path original = directory.resolve("old.pdf"), revised = directory.resolve("new.pdf");
        Path malformed = directory.resolve("broken.pdf"); Files.writeString(malformed, "This is not a PDF.");
        ReviewModel model = new ReviewModel(); List<Notice> notices = new CopyOnWriteArrayList<>();
        ReviewFrame frame = edt(() -> new ReviewFrame(model, (t, m, k) -> notices.add(new Notice(t, m, k))));
        try {
            edt(() -> { frame.selectInputs(original, revised); button(frame, "Analyze PDFs").doClick(0); button(frame, "Cancel").doClick(0); return null; });
            await(() -> !frame.isWorking());
            edt(() -> {
                assertNull(model.plan()); assertEquals(0, model.reviewedCount());
                assertFalse(button(frame, "Export reviewed PDF…").isEnabled()); assertTrue(button(frame, "Analyze PDFs").isEnabled());
                frame.selectInputs(malformed, revised); button(frame, "Analyze PDFs").doClick(0); return null;
            });
            await(() -> !frame.isWorking());
            edt(() -> {
                assertNull(model.plan()); assertFalse(button(frame, "Export reviewed PDF…").isEnabled());
                assertTrue(notices.stream().anyMatch(n -> n.title().equals("Analysis failed")), notices.toString());
                frame.selectInputs(original, revised); button(frame, "Analyze PDFs").doClick(0); return null;
            });
            await(() -> !frame.isWorking());
            edt(() -> { assertNotNull(model.plan()); assertEquals(4, model.unreviewedCount()); assertTrue(button(frame, "Approve").isEnabled()); return null; });
            assertTrue(notices.stream().noneMatch(n -> n.title().equals("Export complete")));
        } finally { edt(() -> { frame.shutdown(); return null; }); await(frame::isStopped); }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) { if (edt(condition::getAsBoolean)) return; Thread.sleep(15); }
        fail("The real Swing background workflow did not reach its expected state within 30 seconds.");
    }
    private static <T> T edt(Callable<T> action) throws Exception { FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(); }
    private static void layout(Container parent) { parent.doLayout(); for (Component c : parent.getComponents()) if (c instanceof Container nested) layout(nested); }
    private static <T> T component(Container parent, Class<T> type) { for (Component c : parent.getComponents()) { if (type.isInstance(c)) return type.cast(c); if (c instanceof Container nested) { T found = component(nested, type); if (found != null) return found; } } return null; }
    private static JButton button(Container parent, String text) { for (Component c : parent.getComponents()) { if (c instanceof JButton b && b.getText().equals(text)) return b; if (c instanceof Container nested) { JButton found = button(nested, text); if (found != null) return found; } } return null; }
    private static JLabel label(Container parent, String text) { for (Component c : parent.getComponents()) { if (c instanceof JLabel label && text.equals(label.getText())) return label; if (c instanceof Container nested) { JLabel found = label(nested, text); if (found != null) return found; } } return null; }
    private static JLabel preview(Container parent, String name) { for (Component c : parent.getComponents()) { if (c instanceof JLabel label && name.equals(label.getAccessibleContext().getAccessibleName())) return label; if (c instanceof Container nested) { JLabel found = preview(nested, name); if (found != null) return found; } } return null; }
}
