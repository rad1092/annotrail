package net.whago.annotrail;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import net.whago.annotrail.Models.*;
import org.junit.jupiter.api.Test;

class ReviewModelTest {
    static Candidate candidate(int page) { return new Candidate(page, "The revised document places this exact text in a new context.", 1, List.of(40f, 100f, 180f, 100f, 40f, 85f, 180f, 85f)); }
    static Entry entry(String id, String status, Candidate... candidates) {
        return new Entry(id, 1, "Highlight", "The retained wording is identical across the revisions.", "Check this section before publication.", "Reviewer", List.of(1f, 1f, 0f), status, "Exact text is matched; meaning must be reviewed.", List.of(candidates));
    }
    static Plan plan(Entry... entries) { return new Plan(1, "0.1.0", new Input("original.pdf", "abc", 100, 2), new Input("revised.pdf", "def", 200, 3), List.of(entries), List.of()); }

    @Test void everythingStartsUnreviewedAndIncompleteExportIsBlocked() {
        ReviewModel model = new ReviewModel(); assertFalse(model.canExport());
        model.load(plan(entry("a", "confident", candidate(2)), entry("b", "removed")));
        assertEquals(2, model.unreviewedCount()); assertEquals(0, model.reviewedCount()); assertFalse(model.canExport());
        assertThrows(IllegalStateException.class, model::exportChoices);
        model.select(0); assertEquals(0, model.selectedCandidate()); assertEquals(0, model.reviewedCount());
        model.approve(); assertEquals(1, model.approvedCount()); assertFalse(model.canExport());
        model.select(1); assertThrows(IllegalStateException.class, model::approve); model.skip();
        assertTrue(model.canExport()); assertEquals(Map.of("a", 0, "b", -1), model.exportChoices());
        assertThrows(UnsupportedOperationException.class, () -> model.exportChoices().put("a", 9));
    }

    @Test void selectingDifferentFindingResetsCandidateAndNeverReusesStaleSelection() {
        ReviewModel model = new ReviewModel(); model.load(plan(entry("a", "ambiguous", candidate(1), candidate(2)), entry("b", "ambiguous", candidate(3))));
        model.select(0); model.selectCandidate(1); model.select(1);
        assertEquals(0, model.selectedCandidate()); model.approve(); assertEquals(0, model.choice(model.selectedEntry()));
        model.select(0); assertEquals(0, model.selectedCandidate()); model.selectCandidate(1); model.approve();
        model.select(1); model.select(0); assertEquals(1, model.selectedCandidate());
        model.selectCandidate(0); assertEquals(1, model.choice(model.selectedEntry()), "Browsing another candidate must not change a saved decision");
        model.select(-1); assertNull(model.candidate()); assertEquals(-1, model.selectedCandidate());
        assertThrows(IllegalStateException.class, model::approve); assertThrows(IllegalArgumentException.class, () -> model.select(5));
    }

    @Test void reanalysisClearsDecisionsEvenWhenIdsAreReused() {
        ReviewModel model = new ReviewModel(); model.load(plan(entry("same", "confident", candidate(1)))); model.acceptConfident(); model.select(0);
        model.load(plan(entry("same", "ambiguous", candidate(2), candidate(3))));
        assertEquals(-1, model.selectedRow()); assertEquals(-1, model.selectedCandidate()); assertEquals(0, model.reviewedCount()); assertFalse(model.canExport());
        model.load(null); assertNull(model.plan()); assertTrue(model.entries().isEmpty()); assertFalse(model.canExport());
    }

    @Test void bulkApprovalDoesNotSkipOrOverrideExplicitDecisions() {
        ReviewModel model = new ReviewModel(); model.load(plan(entry("a", "confident", candidate(1)), entry("b", "confident", candidate(2)), entry("c", "ambiguous", candidate(1), candidate(3)), entry("d", "removed"), entry("e", "confident", candidate(1), candidate(2))));
        model.select(0); model.skip(); model.select(2); model.selectCandidate(1); model.approve();
        assertEquals(1, model.acceptConfident()); assertEquals(2, model.unreviewedCount()); assertEquals(-1, model.choice(model.entries().get(0))); assertEquals(1, model.choice(model.entries().get(2)));
        assertFalse(model.canExport()); assertEquals(2, model.skipUnreviewed()); assertTrue(model.canExport());
        assertEquals(2, model.approvedCount()); assertEquals(3, model.skippedCount());
        model.select(2); model.resetDecision(); assertFalse(model.canExport()); assertNull(model.choice(model.selectedEntry()));
    }

    @Test void invalidCandidatesAndDuplicateIdentifiersAreRejected() {
        ReviewModel model = new ReviewModel(); model.load(plan(entry("a", "confident", candidate(1)))); model.select(0);
        assertThrows(IllegalArgumentException.class, () -> model.selectCandidate(-1)); assertThrows(IllegalArgumentException.class, () -> model.selectCandidate(1));
        assertThrows(IllegalArgumentException.class, () -> model.load(plan(entry("x", "removed"), entry("x", "removed"))));
        assertEquals("a", model.selectedEntry().id(), "An invalid replacement plan must not destroy the current review");
    }

    @Test void emptyAnalysisCanExportAnExplicitEmptyChoiceSet() {
        ReviewModel model = new ReviewModel(); model.load(plan()); assertTrue(model.canExport()); assertEquals(Map.of(), model.exportChoices());
    }
}
