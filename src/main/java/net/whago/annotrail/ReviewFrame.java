package net.whago.annotrail;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import net.whago.annotrail.Models.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Local-only review UI. All PDF work runs outside the event-dispatch thread. */
public final class ReviewFrame extends JPanel {
    private static final Color INK = new Color(29, 45, 61);
    private static final Color MUTED = new Color(89, 106, 120);
    private static final Color ACCENT = new Color(0, 111, 114);
    private static final Color BG = new Color(243, 246, 248);
    private final ReviewModel model;
    @FunctionalInterface interface MessageHandler { void show(String title, String message, int type); }
    private final MessageHandler messages;
    private final JTextField oldPath = pathField("Original annotated PDF");
    private final JTextField newPath = pathField("Revised PDF");
    private final JButton oldBrowse = button("Choose original…", KeyEvent.VK_O);
    private final JButton newBrowse = button("Choose revised…", KeyEvent.VK_R);
    private final JButton analyze = button("Analyze PDFs", KeyEvent.VK_A);
    private final JButton cancel = button("Cancel", KeyEvent.VK_C);
    private final JButton approve = button("Approve", KeyEvent.VK_P);
    private final JButton skip = button("Skip", KeyEvent.VK_S);
    private final JButton reset = button("Reset", KeyEvent.VK_D);
    private final JButton acceptConfident = button("Accept unique matches", KeyEvent.VK_U);
    private final JButton skipUnreviewed = button("Skip unreviewed", KeyEvent.VK_K);
    private final JButton export = button("Export reviewed PDF…", KeyEvent.VK_E);
    private final JLabel status = new JLabel("Choose your original annotated PDF and the revised PDF.");
    private final JLabel counts = new JLabel("No analysis yet");
    private final JLabel matches = new JLabel(" ");
    private final JLabel selectedTitle = new JLabel("Select a finding to review");
    private final JLabel decision = new JLabel("No decision");
    private final JTextArea details = area(5);
    private final JTextArea context = area(3);
    private final JComboBox<String> candidates = new JComboBox<>();
    private final JProgressBar progress = new JProgressBar();
    private final FindingsTable tableModel = new FindingsTable();
    private final JTable table = new JTable(tableModel);
    private final JLabel beforeImage = previewLabel("Original annotation preview");
    private final JLabel afterImage = previewLabel("Revised destination preview");
    private final JLabel beforeTitle = new JLabel("Original PDF");
    private final JLabel afterTitle = new JLabel("Revised PDF");
    private final ThreadPoolExecutor previewExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1), r -> { Thread t = new Thread(r, "annotrail-preview"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private Future<?> previewTask;
    private long previewGeneration;
    private Path original, revised, analyzedOriginal, analyzedRevised;
    private AtomicBoolean cancelRequested = new AtomicBoolean();
    private SwingWorker<?, ?> worker;
    private boolean busy, changingCandidate, closed, closeRequested;

    public static void launch() {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("The review window needs a graphical desktop. Use the CLI in a terminal.");
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (ReflectiveOperationException | UnsupportedLookAndFeelException ignored) { /* The cross-platform theme remains available. */ }
            JFrame frame = new JFrame("Annotrail — review annotation transfers");
            ReviewFrame panel = new ReviewFrame();
            frame.setContentPane(panel);
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) { panel.requestClose(); } });
            frame.setMinimumSize(new Dimension(960, 720));
            frame.setSize(1320, 940);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    public ReviewFrame() { this(new ReviewModel()); }
    ReviewFrame(ReviewModel reviewModel) { this(reviewModel, null); }
    ReviewFrame(ReviewModel reviewModel, MessageHandler messageHandler) {
        model = reviewModel;
        messages = messageHandler;
        setLayout(new BorderLayout(0, 14));
        setBorder(new EmptyBorder(20, 24, 16, 24));
        setBackground(BG);
        add(header(), BorderLayout.NORTH);
        add(workspace(), BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        bindActions();
        refreshCounts();
        refreshControls();
        if (!model.entries().isEmpty()) table.setRowSelectionInterval(0, 0);
    }

    private JComponent header() {
        JPanel header = vertical();
        JLabel title = new JLabel("Annotrail");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        title.setForeground(INK);
        header.add(title);
        JLabel subtitle = new JLabel("Carry annotations into a revised PDF, with every transfer reviewed.");
        subtitle.setForeground(MUTED);
        subtitle.setBorder(new EmptyBorder(5, 0, 13, 0));
        header.add(subtitle);
        JPanel inputs = new JPanel(new GridBagLayout());
        inputs.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 0, 3, 10); g.anchor = GridBagConstraints.WEST;
        addInputRow(inputs, g, 0, "1  Original annotated PDF", oldPath, oldBrowse);
        addInputRow(inputs, g, 1, "2  Revised PDF", newPath, newBrowse);
        g.gridy = 2; g.gridx = 1; g.gridwidth = 2; g.weightx = 1;
        JPanel actions = row(analyze, cancel);
        inputs.add(actions, g);
        header.add(inputs);
        return header;
    }

    private void addInputRow(JPanel panel, GridBagConstraints g, int row, String title, JTextField field, JButton browse) {
        g.gridy = row; g.gridx = 0; g.gridwidth = 1; g.weightx = 0; g.fill = GridBagConstraints.NONE;
        JLabel label = new JLabel(title); label.setLabelFor(field); label.setForeground(INK); panel.add(label, g);
        g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL; panel.add(field, g);
        g.gridx = 2; g.weightx = 0; g.fill = GridBagConstraints.NONE; panel.add(browse, g);
    }

    private JComponent workspace() {
        JPanel findings = new JPanel(new BorderLayout(0, 10)); findings.setOpaque(false);
        JLabel heading = new JLabel("3  Review each finding"); heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f)); heading.setForeground(INK);
        findings.add(heading, BorderLayout.NORTH);
        table.setRowHeight(34); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0)); table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getAccessibleContext().setAccessibleName("Annotation findings");
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
        renderer.putClientProperty("html.disable", Boolean.TRUE); renderer.setBorder(new EmptyBorder(0, 7, 0, 7));
        table.setDefaultRenderer(Object.class, renderer);
        int[] widths = {58, 126, 92, 245};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        JScrollPane tableScroll = new JScrollPane(table); tableScroll.setColumnHeaderView(table.getTableHeader());
        findings.add(tableScroll, BorderLayout.CENTER);
        JPanel findingsFooter = vertical();
        counts.setForeground(INK); findingsFooter.add(counts); matches.setForeground(MUTED); matches.setBorder(new EmptyBorder(4, 0, 0, 0)); findingsFooter.add(matches); findingsFooter.add(Box.createVerticalStrut(8));
        acceptConfident.setToolTipText("Explicitly approve unreviewed findings with one unique exact text match. This is not a semantic guarantee.");
        skipUnreviewed.setToolTipText("Explicitly skip all findings that still have no decision. Skipped comments remain in the JSON report.");
        findingsFooter.add(row(acceptConfident, skipUnreviewed));
        JTextArea note = area(3);
        note.setText("Exact text can appear in a different context. Check the destination. ‘Not found’ does not prove the original content was deleted.");
        note.setForeground(MUTED); note.setBackground(BG); note.setBorder(new EmptyBorder(8, 0, 0, 0)); findingsFooter.add(note);
        findings.add(findingsFooter, BorderLayout.SOUTH);
        findings.setMinimumSize(new Dimension(390, 260));

        JPanel review = new JPanel(new BorderLayout(0, 9)); review.setOpaque(false);
        JPanel top = vertical();
        selectedTitle.setFont(selectedTitle.getFont().deriveFont(Font.BOLD, 15f)); selectedTitle.setForeground(INK); top.add(selectedTitle);
        decision.setForeground(ACCENT); decision.setBorder(new EmptyBorder(5, 0, 7, 0)); top.add(decision);
        details.setBackground(Color.WHITE); details.setBorder(new EmptyBorder(8, 10, 8, 10));
        details.getAccessibleContext().setAccessibleName("Original annotation and matching reason");
        JScrollPane detailsScroll = new JScrollPane(details); detailsScroll.setPreferredSize(new Dimension(550, 110)); top.add(detailsScroll);
        JPanel candidateRow = new JPanel(new BorderLayout(8, 0)); candidateRow.setOpaque(false); candidateRow.setBorder(new EmptyBorder(9, 0, 6, 0));
        JLabel candidateLabel = new JLabel("Destination"); candidateLabel.setLabelFor(candidates); candidateRow.add(candidateLabel, BorderLayout.WEST);
        candidates.getAccessibleContext().setAccessibleName("Candidate destination");
        DefaultListCellRenderer candidateRenderer = new DefaultListCellRenderer(); candidateRenderer.putClientProperty("html.disable", Boolean.TRUE); candidates.setRenderer(candidateRenderer);
        candidateRow.add(candidates, BorderLayout.CENTER); top.add(candidateRow);
        context.setBackground(BG); context.setForeground(MUTED); context.getAccessibleContext().setAccessibleName("Destination context");
        JScrollPane contextScroll = new JScrollPane(context); contextScroll.setPreferredSize(new Dimension(550, 66)); top.add(contextScroll);
        approve.setToolTipText("Approve the selected destination for this finding."); skip.setToolTipText("Skip this finding and keep its original comment in the JSON report."); reset.setToolTipText("Remove the current decision so this finding must be reviewed again.");
        JPanel decisions = row(approve, skip, reset); decisions.setBorder(new EmptyBorder(7, 0, 0, 0)); top.add(decisions);
        review.add(top, BorderLayout.NORTH);
        JSplitPane previews = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                previewPanel(beforeTitle, beforeImage), previewPanel(afterTitle, afterImage));
        previews.setResizeWeight(.5); previews.setBorder(null); previews.setContinuousLayout(true);
        review.add(previews, BorderLayout.CENTER); review.setMinimumSize(new Dimension(420, 300));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, findings, review);
        split.setResizeWeight(.37); split.setDividerLocation(450); split.setBorder(null); split.setContinuousLayout(true);
        return split;
    }

    private JComponent footer() {
        JPanel footer = new JPanel(new BorderLayout(10, 0)); footer.setOpaque(false);
        JPanel statusPanel = new JPanel(new BorderLayout(0, 6)); statusPanel.setOpaque(false);
        status.setForeground(MUTED); status.getAccessibleContext().setAccessibleName("Task status");
        progress.setPreferredSize(new Dimension(240, 5)); progress.setVisible(false);
        statusPanel.add(status, BorderLayout.NORTH); statusPanel.add(progress, BorderLayout.SOUTH);
        footer.add(statusPanel, BorderLayout.CENTER); footer.add(export, BorderLayout.EAST);
        export.setToolTipText("Available after every finding has an explicit approve or skip decision. Creates new PDF and JSON files.");
        return footer;
    }

    private void bindActions() {
        oldBrowse.addActionListener(e -> chooseInput(true)); newBrowse.addActionListener(e -> chooseInput(false));
        analyze.addActionListener(e -> analyze());
        cancel.addActionListener(e -> cancelWork());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) { model.select(table.getSelectedRow()); refreshSelected(); }
        });
        candidates.addActionListener(e -> {
            if (!changingCandidate && candidates.getSelectedIndex() >= 0 && model.selectedEntry() != null) {
                model.selectCandidate(candidates.getSelectedIndex()); refreshCandidate(); refreshControls();
            }
        });
        approve.addActionListener(e -> decide(() -> model.approve(), "Destination approved."));
        skip.addActionListener(e -> decide(() -> model.skip(), "Finding skipped. Its original comment remains in the report."));
        reset.addActionListener(e -> decide(() -> model.resetDecision(), "Decision reset. This finding must be reviewed again."));
        acceptConfident.addActionListener(e -> {
            int accepted = model.acceptConfident(); refreshDecisions();
            status.setText(accepted + " unique exact text matches approved. Review or explicitly skip the remaining findings.");
        });
        skipUnreviewed.addActionListener(e -> {
            int skipped = model.skipUnreviewed(); refreshDecisions();
            status.setText(skipped + " unreviewed findings explicitly skipped. Original comments will remain in the JSON report.");
        });
        export.addActionListener(e -> export());
    }

    private void chooseInput(boolean old) {
        JFileChooser chooser = pdfChooser("Choose " + (old ? "original annotated" : "revised") + " PDF");
        Path current = old ? original : revised;
        if (current != null) chooser.setSelectedFile(current.toFile());
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        selectInputs(old ? path : original, old ? revised : path);
    }

    /** Shared by the native chooser and headless workflow tests; call on the EDT. */
    void selectInputs(Path old, Path next) {
        if (busy) throw new IllegalStateException("Wait for the current operation before changing inputs.");
        original = old == null ? null : old.toAbsolutePath().normalize();
        revised = next == null ? null : next.toAbsolutePath().normalize();
        oldPath.setText(original == null ? "" : original.toString()); oldPath.setCaretPosition(0);
        newPath.setText(revised == null ? "" : revised.toString()); newPath.setCaretPosition(0);
        clearPlan();
        status.setText("Inputs changed. Analyze both PDFs to create a new review.");
        refreshControls();
    }

    private void clearPlan() {
        model.load(null); table.clearSelection(); tableModel.fireTableDataChanged();
        analyzedOriginal = null; analyzedRevised = null; refreshSelected(); refreshCounts();
    }

    private void analyze() {
        if (original == null || revised == null || busy) return;
        Path old = original, next = revised;
        clearPlan(); startWork("Analyzing annotations and exact text matches…");
        AtomicBoolean cancellation = cancelRequested;
        worker = new SwingWorker<Plan, Void>() {
            @Override protected Plan doInBackground() throws Exception { return Rebaser.analyze(old, next, Options.defaults(), cancellation::get); }
            @Override protected void done() {
                try {
                    Plan plan = get();
                    if (cancellation.get()) { status.setText("Analysis cancelled. No review decisions were created."); return; }
                    model.load(plan); analyzedOriginal = old; analyzedRevised = next; tableModel.fireTableDataChanged();
                    if (!model.entries().isEmpty()) table.setRowSelectionInterval(0, 0);
                    status.setText(plan.entries().size() + " findings. Nothing is approved yet." + (plan.warnings().isEmpty() ? "" : " " + plan.warnings().size() + " warnings; see details."));
                    if (!plan.warnings().isEmpty()) showMessage("Analysis warnings", String.join("\n\n", plan.warnings()), JOptionPane.WARNING_MESSAGE);
                } catch (Exception ex) { workError("Analysis failed", ex, cancellation); }
                finally { finishWork(); }
            }
        };
        worker.execute();
    }

    private void export() {
        if (!model.canExport() || busy || analyzedOriginal == null || analyzedRevised == null) return;
        String basename = revised.getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
        JFileChooser pdf = pdfChooser("Save reviewed PDF as a new file");
        pdf.setSelectedFile(revised.resolveSibling(basename + ".annotrail.pdf").toFile());
        if (pdf.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path output = pdf.getSelectedFile().toPath().toAbsolutePath().normalize();
        JFileChooser json = new JFileChooser(); json.setDialogTitle("Save reconciliation report as a new JSON file");
        json.setFileFilter(new FileNameExtensionFilter("JSON report (*.json)", "json"));
        json.setSelectedFile(output.resolveSibling(output.getFileName().toString().replaceFirst("(?i)\\.pdf$", "") + ".report.json").toFile());
        if (json.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path report = json.getSelectedFile().toPath().toAbsolutePath().normalize();
        exportTo(output, report);
    }

    /** Starts the same export service used after the native output choosers; call on the EDT. */
    void exportTo(Path output, Path report) {
        if (!model.canExport() || busy || analyzedOriginal == null || analyzedRevised == null) {
            throw new IllegalStateException("Finish analysis and explicitly approve or skip every finding before exporting.");
        }
        if (Files.exists(output) || Files.exists(report) || output.equals(report)) {
            showMessage("Choose new files", "Existing files cannot be overwritten. Choose two different, new output filenames.", JOptionPane.ERROR_MESSAGE); return;
        }
        Map<String, Integer> choices = model.exportChoices(); Plan plan = model.plan();
        Path old = analyzedOriginal, next = analyzedRevised;
        startWork("Verifying inputs and writing reviewed output…"); AtomicBoolean cancellation = cancelRequested;
        worker = new SwingWorker<Result, Void>() {
            @Override protected Result doInBackground() throws Exception { return Rebaser.export(old, next, plan, choices, output, report, cancellation::get); }
            @Override protected void done() {
                try {
                    Result result = get();
                    status.setText("Export complete: " + result.transferred() + " transferred, " + result.skipped() + " skipped.");
                    showMessage("Export complete", result.transferred() + " annotations transferred; " + result.skipped() + " skipped.\n\nPDF: " + output + "\nReport: " + report
                            + (result.warnings().isEmpty() ? "" : "\n\nWarnings:\n" + String.join("\n", result.warnings())), JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) { workError("Export failed", ex, cancellation); }
                finally { finishWork(); }
            }
        };
        worker.execute();
    }

    private void decide(Runnable action, String message) { action.run(); refreshDecisions(); status.setText(message); }
    private void refreshDecisions() { tableModel.fireTableRowsUpdated(0, Math.max(0, tableModel.getRowCount() - 1)); refreshCounts(); refreshDecision(); refreshControls(); }
    private void refreshCounts() {
        counts.setText(model.plan() == null ? "No analysis yet" : model.approvedCount() + " approved   ·   " + model.skippedCount() + " skipped   ·   " + model.unreviewedCount() + " unreviewed");
        matches.setText(model.plan() == null ? " " : matchCount("confident") + " unique · " + matchCount("ambiguous") + " review · " + matchCount("removed") + " not found · " + matchCount("unsupported") + " unsupported");
    }
    private long matchCount(String status) { return model.entries().stream().filter(e -> status.equals(e.status())).count(); }
    private void refreshDecision() {
        Entry entry = model.selectedEntry(); Integer value = entry == null ? null : model.choice(entry);
        decision.setText(entry == null ? "No finding selected" : value == null ? "Unreviewed — approve a destination or explicitly skip" : value == -1 ? "Skipped — retained in reconciliation report" : "Approved — destination " + (value + 1));
    }
    private void refreshSelected() {
        Entry entry = model.selectedEntry(); changingCandidate = true; candidates.removeAllItems();
        if (entry == null) {
            selectedTitle.setText("Select a finding to review"); details.setText("The original PDFs are preserved. Export creates new files after you review every finding."); context.setText("");
        } else {
            selectedTitle.setText("Page " + entry.sourcePage() + " · " + safe(entry.type()) + " · " + statusName(entry.status()));
            details.setText("Selected text: " + displayText(entry.quote(), 10000) + "\nComment: " + displayText(entry.comment(), 10000) + "\nAuthor: " + displayText(entry.author(), 1000) + "\nReason: " + displayText(entry.reason(), 3000));
            for (int i = 0; i < entry.candidates().size(); i++) {
                Candidate c = entry.candidates().get(i); candidates.addItem((i + 1) + " · Page " + c.page() + " · " + shortText(c.context(), 110));
            }
            if (model.selectedCandidate() >= 0) candidates.setSelectedIndex(model.selectedCandidate());
        }
        changingCandidate = false; details.setCaretPosition(0); refreshDecision(); refreshCandidate(); refreshControls();
    }
    private void refreshCandidate() {
        Candidate candidate = model.candidate();
        context.setText(candidate == null ? "No proposed destination. Review the reason and skip this finding; its comment will remain in the report." : displayText(candidate.context(), 6000));
        context.setCaretPosition(0); requestPreviews();
    }
    private void refreshControls() {
        Entry entry = model.selectedEntry();
        oldBrowse.setEnabled(!busy); newBrowse.setEnabled(!busy); analyze.setEnabled(!busy && original != null && revised != null);
        cancel.setEnabled(busy && !cancelRequested.get());
        table.setEnabled(!busy); candidates.setEnabled(!busy && entry != null && !entry.candidates().isEmpty());
        approve.setEnabled(!busy && model.candidate() != null); skip.setEnabled(!busy && entry != null); reset.setEnabled(!busy && entry != null && model.choice(entry) != null);
        acceptConfident.setEnabled(!busy && model.entries().stream().anyMatch(e -> "confident".equals(e.status()) && e.candidates().size() == 1 && model.choice(e) == null));
        skipUnreviewed.setEnabled(!busy && model.unreviewedCount() > 0); export.setEnabled(!busy && model.canExport() && analyzedOriginal != null);
    }
    private void startWork(String message) { busy = true; cancelRequested = new AtomicBoolean(); status.setText(message); progress.setVisible(true); progress.setIndeterminate(true); cancelPreviews(); refreshControls(); }
    private void cancelWork() { cancelRequested.set(true); status.setText("Cancellation requested. Waiting for the current PDF operation to finish safely…"); refreshControls(); }
    private void finishWork() { busy = false; worker = null; progress.setIndeterminate(false); progress.setVisible(false); refreshCounts(); refreshControls(); if (closeRequested) disposeWindow(); else requestPreviews(); }
    private void workError(String title, Exception exception, AtomicBoolean cancellation) {
        Throwable cause = exception instanceof ExecutionException && exception.getCause() != null ? exception.getCause() : exception;
        if (cancellation.get() || cause instanceof InterruptedIOException || cause instanceof CancellationException) status.setText("Cancelled. No successful completion was reported.");
        else { String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(); status.setText(title + ". Adjust the inputs or choices, then retry."); showMessage(title, message, JOptionPane.ERROR_MESSAGE); }
    }

    private void requestPreviews() {
        cancelPreviews(); Entry entry = model.selectedEntry(); Candidate candidate = model.candidate();
        if (closed || busy || entry == null || analyzedOriginal == null || analyzedRevised == null) { showPreview(beforeImage, null, "Select a finding for the original preview."); showPreview(afterImage, null, "Select a finding for its destination preview."); return; }
        long generation = previewGeneration; Path old = analyzedOriginal, next = analyzedRevised;
        beforeTitle.setText("Original · page " + entry.sourcePage()); afterTitle.setText(candidate == null ? "Revised · no destination" : "Revised · page " + candidate.page());
        showPreview(beforeImage, null, "Rendering page…"); showPreview(afterImage, null, candidate == null ? "No destination to preview." : "Rendering page…");
        previewTask = previewExecutor.submit(() -> {
            renderAndPublish(old, entry.sourcePage(), List.of(), beforeImage, generation);
            if (!Thread.currentThread().isInterrupted() && candidate != null) renderAndPublish(next, candidate.page(), candidate.quads(), afterImage, generation);
        });
    }
    private void renderAndPublish(Path pdf, int page, List<Float> quads, JLabel target, long generation) {
        try {
            BufferedImage result = renderPreview(pdf, page, quads);
            SwingUtilities.invokeLater(() -> { if (!closed && generation == previewGeneration) showPreview(target, result, ""); });
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> { if (!closed && generation == previewGeneration) showPreview(target, null, "Preview unavailable. The full PDF can still be reviewed externally."); });
        }
    }
    static BufferedImage renderPreview(Path path, int page, List<Float> quads) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Preview cancelled.");
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            if (page < 1 || page > doc.getNumberOfPages()) throw new IOException("Preview page is unavailable.");
            PDRectangle crop = doc.getPage(page - 1).getCropBox();
            double width = crop.getWidth(), height = crop.getHeight();
            if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) throw new IOException("Invalid page size.");
            float scale = (float)Math.min(1d, Math.min(1200d / Math.max(width, height), Math.sqrt(1_440_000d / (width * height))));
            if (scale <= 0 || !Float.isFinite(scale)) throw new IOException("Invalid preview scale.");
            PDFRenderer renderer = new PDFRenderer(doc); renderer.setSubsamplingAllowed(true);
            BufferedImage image = renderer.renderImage(page - 1, scale, ImageType.RGB);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Preview cancelled.");
            if (doc.getPage(page - 1).getRotation() == 0 && quads != null && quads.size() % 8 == 0) {
                Graphics2D g = image.createGraphics();
                try {
                    g.setStroke(new BasicStroke(1.6f));
                    for (int i = 0; i < quads.size(); i += 8) {
                        Path2D polygon = new Path2D.Float(); int[] order = {0, 2, 6, 4};
                        for (int j = 0; j < order.length; j++) {
                            int at = i + order[j]; double x = (quads.get(at) - crop.getLowerLeftX()) * scale, y = (crop.getUpperRightY() - quads.get(at + 1)) * scale;
                            if (j == 0) polygon.moveTo(x, y); else polygon.lineTo(x, y);
                        }
                        polygon.closePath(); g.setColor(new Color(0, 140, 145, 65)); g.fill(polygon); g.setColor(ACCENT); g.draw(polygon);
                    }
                } finally { g.dispose(); }
            }
            return image;
        }
    }
    private void cancelPreviews() { previewGeneration++; if (previewTask != null) previewTask.cancel(true); previewExecutor.getQueue().clear(); }
    private void requestClose() {
        if (busy) { closeRequested = true; cancelWork(); status.setText("Closing after cancellation completes safely…"); }
        else disposeWindow();
    }
    private void disposeWindow() { shutdown(); Window window = SwingUtilities.getWindowAncestor(this); if (window != null) window.dispose(); }
    void shutdown() { closed = true; cancelRequested.set(true); cancelPreviews(); previewExecutor.shutdownNow(); }
    boolean isWorking() { return busy; }
    boolean isStopped() { return closed && !busy && previewExecutor.isTerminated(); }
    private void showMessage(String title, String message, int type) {
        if (closeRequested || closed) return;
        if (messages != null) { messages.show(title, message, type); return; }
        JTextArea text = area(8); text.setText(message == null ? "No details available." : message); text.setCaretPosition(0); text.setBackground(UIManager.getColor("Panel.background"));
        JScrollPane scroll = new JScrollPane(text); scroll.setPreferredSize(new Dimension(570, Math.min(330, 100 + text.getLineCount() * 17)));
        JOptionPane.showMessageDialog(this, scroll, title, type);
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String displayText(String value, int max) { String text = safe(value); return text.length() <= max ? text : text.substring(0, max) + "… [display shortened; full text remains in the report]"; }
    private static String shortText(String value, int max) { String line = displayText(value, Math.max(4096, max)).replaceAll("\\s+", " ").trim(); return line.length() <= max ? line : line.substring(0, max - 1) + "…"; }
    static String statusName(String status) { return switch (safe(status)) { case "confident" -> "Unique exact text"; case "ambiguous" -> "Needs review"; case "removed" -> "Not found"; case "unsupported" -> "Unsupported"; default -> "Needs review"; }; }
    private static JButton button(String text, int mnemonic) { JButton b = new JButton(text); b.setMnemonic(mnemonic); b.setFocusPainted(true); return b; }
    private static JTextField pathField(String name) { JTextField f = new JTextField(); f.setEditable(false); f.getAccessibleContext().setAccessibleName(name); f.setColumns(30); return f; }
    private static JTextArea area(int rows) { JTextArea a = new JTextArea(rows, 25); a.setEditable(false); a.setFocusTraversalKeysEnabled(true); a.setLineWrap(true); a.setWrapStyleWord(true); a.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12)); return a; }
    private static JPanel vertical() { JPanel panel = new JPanel() { @Override protected void addImpl(Component component, Object constraints, int index) { if (component instanceof JComponent child) child.setAlignmentX(Component.LEFT_ALIGNMENT); if (component instanceof JLabel label) label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height)); super.addImpl(component, constraints, index); } }; panel.setOpaque(false); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); return panel; }
    private static JPanel row(Component... components) { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); p.setOpaque(false); for (Component c : components) p.add(c); return p; }
    private static JLabel previewLabel(String accessibleName) { JLabel label = new JLabel("Choose PDFs and analyze to see previews.", SwingConstants.CENTER); label.setVerticalAlignment(SwingConstants.TOP); label.setOpaque(true); label.setBackground(new Color(226, 231, 234)); label.setForeground(MUTED); label.setBorder(new EmptyBorder(10, 10, 10, 10)); label.getAccessibleContext().setAccessibleName(accessibleName); return label; }
    private static JComponent previewPanel(JLabel title, JLabel image) { JPanel panel = new JPanel(new BorderLayout(0, 6)); panel.setOpaque(false); title.setForeground(INK); panel.add(title, BorderLayout.NORTH); JScrollPane scroll = new JScrollPane(image); scroll.setPreferredSize(new Dimension(290, 310)); panel.add(scroll, BorderLayout.CENTER); panel.setMinimumSize(new Dimension(100, 100)); return panel; }
    private static void showPreview(JLabel target, BufferedImage image, String text) { target.setIcon(image == null ? null : new ImageIcon(image)); target.setText(image == null ? text : ""); }
    private static JFileChooser pdfChooser(String title) { JFileChooser c = new JFileChooser(); c.setDialogTitle(title); c.setFileFilter(new FileNameExtensionFilter("PDF documents (*.pdf)", "pdf")); return c; }

    private final class FindingsTable extends AbstractTableModel {
        private final String[] columns = {"Page", "Match", "Decision", "Selected text / comment"};
        @Override public int getRowCount() { return model.entries().size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) {
            Entry entry = model.entries().get(row); Integer choice = model.choice(entry);
            return switch (column) { case 0 -> String.valueOf(entry.sourcePage()); case 1 -> statusName(entry.status()); case 2 -> choice == null ? "Unreviewed" : choice == -1 ? "Skipped" : "Approved"; default -> shortText(entry.quote() == null || entry.quote().isBlank() ? entry.comment() : entry.quote(), 130); };
        }
    }
}
