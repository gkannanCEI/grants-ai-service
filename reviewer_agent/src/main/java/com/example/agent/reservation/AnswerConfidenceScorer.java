package com.example.agent.reservation;

import java.util.List;

/**
 * Computes the 0–10 confidence score attached to every supplemental-question answer.
 *
 * <p>The model's own self-rating is never trusted on its own — LLMs routinely report high
 * confidence for weakly supported answers. Instead the final score is the model's rating
 * <em>capped by the strength of the evidence it actually cited</em>, where the evidence is
 * judged from the resolved {@link SupplementalQaService.SourceRef}s (i.e. citations we could
 * verify against real retrieved chunks), not from anything the model claims:</p>
 *
 * <table>
 *   <tr><th>Best verified evidence</th><th>Cap</th></tr>
 *   <tr><td>Document / personal-knowledge excerpt resolved to a real chunk</td><td>10</td></tr>
 *   <tr><td>Reservation AI summary (or an unverifiable document label)</td><td>8</td></tr>
 *   <tr><td>Custom AI instruction (hint) only</td><td>5</td></tr>
 *   <tr><td>No usable source</td><td>3</td></tr>
 * </table>
 *
 * <p>When the model gives no usable rating, the score falls back to the evidence cap, further
 * limited to {@value #MAX_WITHOUT_MODEL_RATING} — without the model's own assessment we can't
 * claim high confidence even if the evidence looks strong.</p>
 */
final class AnswerConfidenceScorer {

    static final int MIN_SCORE = 0;
    static final int MAX_SCORE = 10;

    static final int CAP_VERIFIED_EXCERPT = 10;
    static final int CAP_SUMMARY          = 8;
    static final int CAP_HINT_ONLY        = 5;
    static final int CAP_NO_SOURCES       = 3;

    /** Ceiling applied when the model did not return a usable self-rating. */
    static final int MAX_WITHOUT_MODEL_RATING = 6;

    private AnswerConfidenceScorer() { }

    /**
     * @param modelRating the model's self-reported 0–10 rating, or null if absent/invalid
     * @param sources     the resolved citations for the answer (never null)
     * @return the final confidence score, always within 0–10
     */
    static int score(Integer modelRating, List<SupplementalQaService.SourceRef> sources) {
        int cap = evidenceCap(sources);
        int base = modelRating != null ? clamp(modelRating) : Math.min(cap, MAX_WITHOUT_MODEL_RATING);
        return Math.min(base, cap);
    }

    /** The highest score the verified evidence can justify. */
    static int evidenceCap(List<SupplementalQaService.SourceRef> sources) {
        if (sources == null || sources.isEmpty()) {
            return CAP_NO_SOURCES;
        }
        boolean hasVerifiedExcerpt = false;
        boolean hasSummary = false;
        boolean hasHint = false;
        for (SupplementalQaService.SourceRef s : sources) {
            if (s == null || s.type() == null) {
                continue;
            }
            switch (s.type()) {
                // A snippet is only present when the cited label resolved to a real chunk.
                case "DOCUMENT", "USER_KNOWLEDGE" -> {
                    if (s.snippet() != null) {
                        hasVerifiedExcerpt = true;
                    } else {
                        hasSummary = true;
                    }
                }
                case "SUMMARY" -> hasSummary = true;
                case "HINT" -> hasHint = true;
                default -> { /* unknown type — contributes no evidence */ }
            }
        }
        if (hasVerifiedExcerpt) return CAP_VERIFIED_EXCERPT;
        if (hasSummary)         return CAP_SUMMARY;
        if (hasHint)            return CAP_HINT_ONLY;
        return CAP_NO_SOURCES;
    }

    static int clamp(int value) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, value));
    }
}
