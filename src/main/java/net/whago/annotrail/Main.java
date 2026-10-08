package net.whago.annotrail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** File-first command line. No input file is used as an output. */
public final class Main {
    public static final String VERSION = "0.1.1";
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().setStrictness(Strictness.STRICT).create();
    private Main() {}

    public static void main(String[] args) {
        try {
            Path cache = Files.createTempDirectory("annotrail-font-cache-");
            System.setProperty("pdfbox.fontcache", cache.toString());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (var children = Files.list(cache)) {
                    children.forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
                } catch (IOException ignored) { }
                try { Files.deleteIfExists(cache); } catch (IOException ignored) { }
            }, "annotrail-font-cache-cleanup"));
        } catch (IOException failure) {
            System.err.println("annotrail: unable to create temporary font cache");
            System.exit(2); return;
        }
        // jpackage's default desktop argument precedes optional launcher CLI arguments.
        if (args.length > 1 && args[0].equals("gui")) args = java.util.Arrays.copyOfRange(args, 1, args.length);
        int code = run(args);
        // Swing's event thread owns the desktop lifecycle after launch().
        if (code == 0 && args.length > 0 && args[0].equals("gui")) return;
        System.exit(code);
    }

    public static int run(String[] args) {
        try {
            if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
                help(); return 0;
            }
            if (args[0].equals("--version")) { System.out.println("annotrail " + VERSION); return 0; }
            if (args[0].equals("gui")) {
                if (args.length != 1) throw new IllegalArgumentException("gui takes no arguments");
                if (java.awt.GraphicsEnvironment.isHeadless()) throw new IllegalArgumentException("Desktop unavailable. Use analyze/export in this environment.");
                ReviewFrame.launch();
                return 0;
            }
            String command = args[0];
            if (!Set.of("analyze", "export").contains(command)) throw new IllegalArgumentException("Unknown command; use --help");
            Map<String, String> options = parse(args);
            Path oldPdf = required(options, "old");
            Path newPdf = required(options, "new");
            Path planPath = required(options, "plan");
            if (command.equals("analyze")) {
                rejectExtra(options, Set.of("old", "new", "plan"));
                ensureNew(planPath, oldPdf, newPdf);
                Models.Plan plan = Rebaser.analyze(oldPdf, newPdf, Models.Options.defaults(), () -> Thread.currentThread().isInterrupted());
                writeNew(planPath, JSON.toJson(plan));
                summary(plan);
                System.out.println("Plan written. Review every proposed mapping before export.");
                return plan.entries().stream().allMatch(e -> e.status().equals("confident")) ? 0 : 1;
            }
            rejectExtra(options, Set.of("old", "new", "plan", "choices", "accept-confident", "output", "report"));
            if (options.containsKey("choices") == options.containsKey("accept-confident")) throw new IllegalArgumentException("Use exactly one of --choices FILE or --accept-confident");
            Path output = required(options, "output"), report = required(options, "report");
            ensureNew(output, oldPdf, newPdf, planPath);
            ensureNew(report, oldPdf, newPdf, planPath, output);
            Models.Plan plan = JSON.fromJson(readBounded(planPath, 32 * 1024 * 1024), Models.Plan.class);
            if (plan == null || plan.entries() == null) throw new IllegalArgumentException("Invalid plan");
            Map<String, Integer> choices;
            if (options.containsKey("accept-confident")) {
                choices = new LinkedHashMap<>();
                for (Models.Entry entry : plan.entries()) choices.put(entry.id(), entry.status().equals("confident") ? 0 : -1);
            } else {
                Path choicesPath = required(options, "choices");
                ensureNew(output, choicesPath);
                ensureNew(report, choicesPath);
                choices = readChoices(readBounded(choicesPath, 1024 * 1024));
            }
            Models.Result result = Rebaser.export(oldPdf, newPdf, plan, choices, output, report, () -> Thread.currentThread().isInterrupted());
            System.out.printf("Exported %d annotations; skipped %d. Originals preserved.%n", result.transferred(), result.skipped());
            return 0;
        } catch (java.util.concurrent.CancellationException | java.io.InterruptedIOException e) {
            System.err.println("Cancelled; no completed output."); return 130;
        } catch (OutOfMemoryError exhausted) {
            System.err.println("annotrail: PDF processing exceeded the configured Java heap. Use smaller PDFs."); return 2;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            System.err.println("annotrail: " + escaped(message)); return 2;
        }
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Expected --option, use --help");
            String key = args[i].substring(2);
            String value;
            if (key.equals("accept-confident")) value = "true";
            else {
                if (++i >= args.length || args[i].startsWith("--")) throw new IllegalArgumentException("Missing value for --" + key);
                value = args[i];
            }
            if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Repeated option --" + key);
        }
        return result;
    }

    static Map<String, Integer> readChoices(String text) throws IOException {
        Map<String, Integer> result = new LinkedHashMap<>();
        try (JsonReader r = new JsonReader(new StringReader(text))) {
            r.setStrictness(Strictness.STRICT);
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                if (r.peek() != JsonToken.NUMBER) throw new IllegalArgumentException("Every choice must be an integer candidate index or -1");
                String value = r.nextString();
                if (!value.matches("-?(0|[1-9][0-9]*)")) throw new IllegalArgumentException("Every choice must be an integer");
                if (result.putIfAbsent(name, Integer.parseInt(value)) != null) throw new IllegalArgumentException("Duplicate annotation id in choices");
            }
            r.endObject();
            if (r.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing data in choices");
        }
        return result;
    }

    private static Path required(Map<String, String> options, String key) {
        if (!options.containsKey(key)) throw new IllegalArgumentException("Required option --" + key);
        return Path.of(options.get(key));
    }
    private static void rejectExtra(Map<String, String> options, Set<String> allowed) {
        for (String key : options.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unexpected option --" + key);
    }
    private static void ensureNew(Path output, Path... inputs) throws IOException {
        if (Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("Output already exists; choose a new filename");
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("Output parent directory must exist");
        Path canonical = parent.toRealPath().resolve(output.getFileName());
        for (Path input : inputs) {
            Path ip = Files.exists(input) ? input.toRealPath() : input.toAbsolutePath().normalize();
            if (canonical.equals(ip)) throw new IOException("Outputs must differ from every input and one another");
        }
    }
    private static String readBounded(Path path, int maximum) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > maximum) throw new IOException("Input JSON must be a regular file within the size limit");
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("Input JSON exceeds size limit");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    private static void writeNew(Path target, String text) throws IOException {
        // The plan is an audit artifact. Exclusive create avoids any overwrite.
        boolean created = false;
        try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            created = true;
            out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            if (created) Files.deleteIfExists(target);
            throw e;
        }
    }
    private static void summary(Models.Plan plan) {
        long confident = plan.entries().stream().filter(e -> e.status().equals("confident")).count();
        long ambiguous = plan.entries().stream().filter(e -> e.status().equals("ambiguous")).count();
        long absent = plan.entries().stream().filter(e -> e.status().equals("removed")).count();
        System.out.printf("%d annotations: %d unique exact text, %d require review, %d not found, %d unsupported.%n", plan.entries().size(), confident, ambiguous, absent, plan.entries().size() - confident - ambiguous - absent);
    }
    private static String escaped(String value) {
        StringBuilder s = new StringBuilder();
        value.codePoints().limit(1000).forEach(c -> { if (Character.isISOControl(c)) s.append(String.format("\\u%04x", c)); else s.appendCodePoint(c); });
        return s.toString();
    }
    private static void help() {
        System.out.println("""
Annotrail 0.1.1 — reviewed PDF annotation transfer across revisions

  annotrail gui
  annotrail analyze --old annotated.pdf --new revised.pdf --plan plan.json
  annotrail export --old annotated.pdf --new revised.pdf --plan plan.json
      --choices choices.json --output transferred.pdf --report reconciliation.json

Choices JSON: {"annotation-id": 0, "other-id": -1}
Candidate indices start at 0; -1 explicitly skips. Every annotation needs a choice.
Use --accept-confident instead of --choices to explicitly accept unique exact
text matches and skip all unresolved annotations. Review the plan first.
Inputs remain unchanged; all output filenames must be new.
Supported: embedded Highlight, Underline, StrikeOut and Text annotations.
No OCR, handwritten ink, encrypted PDFs, rotated text, or remote services.
Exit 0 complete; analyze exit 1 needs review; 2 error; 130 cancelled.
""");
    }
}
