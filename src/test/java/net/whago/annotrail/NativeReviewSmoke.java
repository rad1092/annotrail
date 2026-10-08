package net.whago.annotrail;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;

/**
 * Opt-in native-window smoke runner. Requires a logged-in graphical desktop.
 * Drives only this process's Swing controls, never OS input or screen capture.
 * Native choosers and manual mouse/keyboard interaction remain unverified.
 * Run in a subprocess with an external 60-second timeout as a second bound.
 */
public final class NativeReviewSmoke {
    private static final long START = System.nanoTime();
    private static final long DEADLINE = START + TimeUnit.SECONDS.toNanos(50);
    private static JFrame window;
    private static ReviewFrame panel;
    private static final Map<String, Object> evidence = new LinkedHashMap<>();

    private NativeReviewSmoke() { }

    public static void main(String[] arguments) {
        int exitCode = 1;
        Path directory = null;
        // A stalled macOS EDT must not leave this test's app running indefinitely.
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(55_000); }
            catch (InterruptedException ignored) { return; }
            System.err.println("FAIL: native smoke exceeded its 55-second process bound.");
            Runtime.getRuntime().halt(124);
        }, "annotrail-native-smoke-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            require(arguments.length == 1, "Usage: NativeReviewSmoke NEW_OUTPUT_DIRECTORY");
            Path requested = Path.of(arguments[0]).toAbsolutePath().normalize();
            Files.createDirectory(requested); // Never overwrite an earlier test or user data.
            directory = requested;
            evidence.put("startedAt", Instant.now().toString());
            evidence.put("mode", "Visible native JFrame, application-internal automation");
            evidence.put("manualInputAndFileChooserTested", false);
            evidence.put("screenCaptureUsed", false);
            evidence.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
            evidence.put("java", System.getProperty("java.version"));
            require(!GraphicsEnvironment.isHeadless(), "A graphical desktop is unavailable.");
            System.setProperty("pdfbox.fontcache", directory.toString());
            run(directory);
            evidence.put("result", "PASS");
            exitCode = 0;
        } catch (Throwable failure) {
            evidence.put("result", "FAIL");
            evidence.put("error", failure.toString());
            failure.printStackTrace(System.err);
        } finally {
            try {
                edt(() -> {
                    if (panel != null) panel.shutdown();
                    if (window != null) window.dispose();
                    return null;
                });
                if (panel != null) await(panel::isStopped, "Own background workers stopped");
                if (window != null) require(edt(() -> !window.isDisplayable()), "Own native window did not close.");
                evidence.put("ownWindowDisposed", true);
            } catch (Throwable cleanupFailure) {
                evidence.put("cleanupError", cleanupFailure.toString());
                evidence.put("result", "FAIL");
                exitCode = 1;
            }
            evidence.put("elapsedSeconds", (System.nanoTime() - START) / 1_000_000_000.0);
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(evidence);
            if (directory != null && Files.isDirectory(directory)) {
                try { Files.writeString(directory.resolve("result.json"), json + "\n"); }
                catch (Exception writeFailure) { writeFailure.printStackTrace(System.err); exitCode = 1; }
            }
            System.out.println(json);
            watchdog.interrupt();
        }
        // The process belongs exclusively to this runner; no other apps are closed.
        System.exit(exitCode);
    }

    private static void run(Path directory) throws Exception {
        FixtureGenerator.generate(directory);
        Path original = directory.resolve("old.pdf"), revised = directory.resolve("new.pdf");
        Path output = directory.resolve("reviewed.pdf"), report = directory.resolve("reviewed.json");
        byte[] originalBytes = Files.readAllBytes(original), revisedBytes = Files.readAllBytes(revised);
        ReviewModel model = new ReviewModel();
        List<String> errors = new CopyOnWriteArrayList<>();
        List<String> notices = new CopyOnWriteArrayList<>();
        AtomicBoolean opened = new AtomicBoolean();
        edt(() -> {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            panel = new ReviewFrame(model, (title, message, type) -> {
                notices.add(title);
                if (type == JOptionPane.ERROR_MESSAGE) errors.add(title + ": " + message);
            });
            window = new JFrame("Annotrail — automated native-window verification");
            window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            window.addWindowListener(new WindowAdapter() {
                @Override public void windowOpened(WindowEvent event) { opened.set(true); }
            });
            window.setContentPane(panel);
            window.setMinimumSize(new Dimension(960, 720));
            window.setSize(1320, 940);
            window.setLocationRelativeTo(null);
            window.setVisible(true);
            return null;
        });
        await(() -> opened.get() && window.isDisplayable() && window.isShowing()
                && panel.isShowing() && window.getGraphicsConfiguration() != null,
                "Visible native window and component peer");
        evidence.put("nativeWindowOpenedAndShowing", true);
        evidence.put("lookAndFeel", edt(() -> UIManager.getLookAndFeel().getName()));
        edt(() -> {
            panel.selectInputs(original, revised);
            button("Analyze PDFs").doClick(0);
            require(panel.isWorking(), "Analyze button did not start the real worker.");
            require(!button("Choose original…").isEnabled(), "Inputs remained enabled while analyzing.");
            return null;
        });
        await(() -> !panel.isWorking(), "PDF analysis");
        edt(() -> {
            require(model.plan() != null, "Analysis failed: " + errors);
            JTable table = component(panel, JTable.class);
            require(table.getRowCount() == 4, "Expected four annotation findings.");
            require(!button("Export reviewed PDF…").isEnabled(), "Export enabled before review.");
            table.setRowSelectionInterval(1, 1);
            JComboBox<?> candidates = component(panel, JComboBox.class);
            require(candidates.getItemCount() == 2, "Expected two repeated-text destinations.");
            candidates.setSelectedIndex(1);
            button("Approve").doClick(0);
            require(model.choice(model.entries().get(1)) == 1, "Second destination was not approved.");
            table.setRowSelectionInterval(0, 0); button("Approve").doClick(0);
            table.setRowSelectionInterval(2, 2); button("Skip").doClick(0);
            table.setRowSelectionInterval(3, 3); button("Skip").doClick(0);
            require(model.approvedCount() == 2 && model.skippedCount() == 2, "Review count mismatch.");
            require(button("Export reviewed PDF…").isEnabled(), "Reviewed export not enabled.");
            table.setRowSelectionInterval(1, 1);
            return null;
        });
        await(NativeReviewSmoke::previewsReady, "Original and destination PDF previews");
        edt(() -> {
            BufferedImage painted = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = painted.createGraphics();
            try { panel.printAll(graphics); } finally { graphics.dispose(); }
            require(ImageIO.write(painted, "png", directory.resolve("internal-swing-render.png").toFile()), "PNG writer unavailable.");
            // Shared implementation called after the native chooser returns paths.
            panel.exportTo(output, report);
            require(panel.isWorking(), "Export did not start the real worker.");
            return null;
        });
        await(() -> !panel.isWorking(), "Reviewed PDF export");
        require(errors.isEmpty(), "Workflow errors: " + errors);
        require(notices.contains("Export complete"), "No successful export notification.");
        require(Files.isRegularFile(output) && Files.isRegularFile(report), "Missing output PDF or report.");
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            require(document.getNumberOfPages() == 2, "Reopened PDF page count differs.");
            require(document.getPage(0).getAnnotations().size() == 1, "Existing revised annotation changed.");
            require("Existing revised annotation.".equals(document.getPage(0).getAnnotations().get(0).getContents()), "Existing comment changed.");
            require(document.getPage(1).getAnnotations().size() == 2, "Transferred annotation count differs.");
            PDAnnotationTextMarkup selected = (PDAnnotationTextMarkup) document.getPage(1).getAnnotations().get(1);
            require("Choose the correct repeated section.".equals(selected.getContents()), "Selected comment changed.");
            require(Math.abs(model.entries().get(1).candidates().get(1).quads().get(1) - selected.getQuadPoints()[1]) < .01,
                    "Export used the wrong repeated-text candidate.");
        }
        var result = JsonParser.parseString(Files.readString(report)).getAsJsonObject().getAsJsonObject("result");
        require(result.get("transferred").getAsInt() == 2 && result.get("skipped").getAsInt() == 2, "Report counts differ.");
        evidence.put("analyzedAnnotations", 4);
        evidence.put("approvedAnnotations", 2);
        evidence.put("skippedAnnotations", 2);
        evidence.put("existingRevisedAnnotationPreserved", true);
        evidence.put("secondRepeatedCandidateVerified", true);
        evidence.put("previewCount", 2);
        evidence.put("renderIsOwnSwingContentOnly", true);
        byte[] outputBytes = Files.readAllBytes(output);
        edt(() -> { panel.selectInputs(output, revised); button("Analyze PDFs").doClick(0); return null; });
        await(() -> !panel.isWorking(), "Reopening the exported PDF in the visible review app");
        edt(() -> {
            require(model.plan() != null && model.entries().size() == 3, "Reopened GUI findings differ: " + errors);
            component(panel, JTable.class).setRowSelectionInterval(1, 1);
            return null;
        });
        await(NativeReviewSmoke::previewsReady, "Reopened exported PDF preview");
        require(errors.isEmpty(), "Reopen errors: " + errors);
        require(Arrays.equals(outputBytes, Files.readAllBytes(output)), "Reopening changed the output PDF.");
        require(Arrays.equals(originalBytes, Files.readAllBytes(original)), "Original input changed.");
        require(Arrays.equals(revisedBytes, Files.readAllBytes(revised)), "Revised input changed.");
        evidence.put("exportReopenedInVisibleApp", true);
        evidence.put("reopenedAnnotationCount", 3);
        evidence.put("allInputAndReopenedOutputBytesPreserved", true);
    }

    private static boolean previewsReady() {
        JLabel original = preview(panel, "Original annotation preview");
        JLabel revised = preview(panel, "Revised destination preview");
        return original != null && revised != null
                && original.getIcon() instanceof ImageIcon old && old.getIconWidth() > 100
                && revised.getIcon() instanceof ImageIcon next && next.getIconHeight() > 100;
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static void await(BooleanSupplier condition, String description) throws Exception {
        while (System.nanoTime() < DEADLINE) {
            if (edt(condition::getAsBoolean)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out: " + description);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }

    private static JButton button(String text) {
        JButton found = findButton(panel, text);
        if (found == null) throw new AssertionError("Missing Swing button: " + text);
        return found;
    }

    private static JButton findButton(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton candidate && text.equals(candidate.getText())) return candidate;
            if (child instanceof Container nested) { JButton found = findButton(nested, text); if (found != null) return found; }
        }
        return null;
    }

    private static <T> T component(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) { T found = component(nested, type); if (found != null) return found; }
        }
        return null;
    }

    private static JLabel preview(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JLabel label && name.equals(label.getAccessibleContext().getAccessibleName())) return label;
            if (child instanceof Container nested) { JLabel found = preview(nested, name); if (found != null) return found; }
        }
        return null;
    }
}
