package com.dartintel.api.summarization;

import java.time.LocalDate;

/**
 * Inputs the LLM sees when summarising a single DART disclosure.
 *
 * <p>{@code body} is the plain-text rendering of the per-filing
 * {@code /api/document.xml} ZIP, capped at {@link
 * com.dartintel.api.ingestion.DartDocumentParser#MAX_BODY_CHARS} chars.
 * Populated lazily on the first paid call for this {@code rcptNo} —
 * {@code null} or empty when the body is unavailable (DART 404 on
 * very fresh filings, withdrawn filings, body-fetch breaker open).
 * The Gemini prompt branches on its presence so the model knows
 * whether quantitative facts are visible or whether it must fall
 * back to title-only summarisation.
 */
public record DisclosureContext(
        String rcptNo,
        String corpCode,
        String corpName,
        String corpNameEng,
        String reportNm,
        LocalDate rceptDt,
        String rm,
        String body
) {
    /**
     * Convenience for tests / call sites that don't have a body —
     * preserves the pre-v1.1 7-arg signature where most callers
     * lived. Production code under {@link SummaryService} always
     * uses the full 8-arg form.
     */
    public DisclosureContext(
            String rcptNo,
            String corpCode,
            String corpName,
            String corpNameEng,
            String reportNm,
            LocalDate rceptDt,
            String rm
    ) {
        this(rcptNo, corpCode, corpName, corpNameEng, reportNm, rceptDt, rm, null);
    }

    /** {@code true} when a non-empty body is available for the LLM prompt. */
    public boolean hasBody() {
        return body != null && !body.isBlank();
    }
}
