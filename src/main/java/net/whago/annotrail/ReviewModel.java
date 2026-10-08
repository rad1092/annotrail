package net.whago.annotrail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.whago.annotrail.Models.Candidate;
import net.whago.annotrail.Models.Entry;
import net.whago.annotrail.Models.Plan;

/** Explicit, in-memory review decisions. It never infers approval from a match. */
public final class ReviewModel {
    private Plan plan;
    private final Map<String, Integer> choices = new LinkedHashMap<>();
    private int selectedRow = -1;
    private int selectedCandidate = -1;

    public void load(Plan next) {
        if (next != null) {
            var ids = new java.util.HashSet<String>();
            for (Entry entry : next.entries()) {
                if (entry == null || entry.id() == null || entry.id().isBlank() || !ids.add(entry.id())) {
                    throw new IllegalArgumentException("Each finding must have a unique nonempty identifier.");
                }
                Objects.requireNonNull(entry.candidates(), "Candidates are required.");
            }
        }
        plan = next;
        choices.clear();
        selectedRow = -1;
        selectedCandidate = -1;
    }

    public Plan plan() { return plan; }
    public List<Entry> entries() { return plan == null ? List.of() : plan.entries(); }
    public int selectedRow() { return selectedRow; }
    public int selectedCandidate() { return selectedCandidate; }
    public Entry selectedEntry() { return selectedRow < 0 ? null : entries().get(selectedRow); }
    public Integer choice(Entry entry) { return choices.get(entry.id()); }
    public int reviewedCount() { return choices.size(); }
    public int unreviewedCount() { return entries().size() - choices.size(); }
    public int approvedCount() { return (int) choices.values().stream().filter(n -> n >= 0).count(); }
    public int skippedCount() { return (int) choices.values().stream().filter(n -> n == -1).count(); }
    public boolean canExport() { return plan != null && unreviewedCount() == 0; }

    public void select(int row) {
        if (row < -1 || row >= entries().size()) throw new IllegalArgumentException("Unknown finding.");
        if (row == selectedRow) return;
        selectedRow = row;
        Entry entry = selectedEntry();
        Integer chosen = entry == null ? null : choices.get(entry.id());
        selectedCandidate = entry == null || entry.candidates().isEmpty() ? -1
                : chosen != null && chosen >= 0 ? chosen : 0;
    }

    public void selectCandidate(int index) {
        Entry entry = requireSelection();
        if (index < 0 || index >= entry.candidates().size()) {
            throw new IllegalArgumentException("Choose an available destination first.");
        }
        selectedCandidate = index;
    }

    public Candidate candidate() {
        Entry entry = selectedEntry();
        return entry == null || selectedCandidate < 0 ? null : entry.candidates().get(selectedCandidate);
    }

    public void approve() {
        Entry entry = requireSelection();
        if (selectedCandidate < 0 || selectedCandidate >= entry.candidates().size()) {
            throw new IllegalStateException("This finding has no destination to approve. Review its reason, then skip it.");
        }
        choices.put(entry.id(), selectedCandidate);
    }

    public void skip() { choices.put(requireSelection().id(), -1); }
    public void resetDecision() { choices.remove(requireSelection().id()); }

    /** Only explicit invocation approves currently unreviewed unique exact matches. */
    public int acceptConfident() {
        int count = 0;
        for (Entry entry : entries()) {
            if ("confident".equals(entry.status()) && entry.candidates().size() == 1 && !choices.containsKey(entry.id())) {
                choices.put(entry.id(), 0);
                count++;
            }
        }
        return count;
    }

    /** The user invokes this separately; bulk approval never silently skips. */
    public int skipUnreviewed() {
        int count = 0;
        for (Entry entry : entries()) {
            if (!choices.containsKey(entry.id())) {
                choices.put(entry.id(), -1);
                count++;
            }
        }
        return count;
    }

    public Map<String, Integer> exportChoices() {
        if (!canExport()) throw new IllegalStateException("Approve or skip every finding before exporting.");
        for (Entry entry : entries()) {
            int value = choices.get(entry.id());
            if (value < -1 || value >= entry.candidates().size()) {
                throw new IllegalStateException("A review decision is no longer valid; analyze the PDFs again.");
            }
        }
        return Map.copyOf(choices);
    }

    private Entry requireSelection() {
        Entry entry = selectedEntry();
        if (entry == null) throw new IllegalStateException("Select a finding first.");
        return entry;
    }
}
