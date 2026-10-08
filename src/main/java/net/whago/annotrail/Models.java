package net.whago.annotrail;

import java.util.List;

/** Versioned, path-free review data. Candidate page numbers are one-based. */
public final class Models {
    private Models() { }
    public record Options(int maxPages, int maxCharacters, int maxAnnotations, int maxCandidates) {
        public static Options defaults() { return new Options(500, 2_000_000, 2_000, 20); }
    }
    public record Input(String name, String sha256, long bytes, int pages) { }
    public record Candidate(int page, String context, double score, List<Float> quads) {
        public Candidate { quads = List.copyOf(quads); }
    }
    public record Entry(String id, int sourcePage, String type, String quote, String comment,
                        String author, List<Float> color, String status, String reason,
                        List<Candidate> candidates) {
        public Entry { color = List.copyOf(color); candidates = List.copyOf(candidates); }
    }
    public record Plan(int formatVersion, String version, Input original, Input revised,
                       List<Entry> entries, List<String> warnings) {
        public Plan { entries = List.copyOf(entries); warnings = List.copyOf(warnings); }
    }
    public record Result(int transferred, int skipped, List<String> warnings) {
        public Result { warnings = List.copyOf(warnings); }
    }
}
