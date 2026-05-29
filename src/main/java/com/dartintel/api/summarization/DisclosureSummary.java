package com.dartintel.api.summarization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "disclosure_summary")
public class DisclosureSummary {

    @Id
    @Column(name = "rcpt_no", length = 14, nullable = false, updatable = false)
    private String rcptNo;

    // Backed by TEXT in V8. Null while the row is in the rule-based
    // "classifier-only" state from round-15c: importance / event type
    // / tags are populated by `DisclosureClassifier` at ingestion, but
    // the English summary text is the paid bit. The first paid /summary
    // or /by-ticker call triggers `SummaryService.summarize` which
    // UPDATEs the same row with the LLM-generated summaryEn (and may
    // refine the classification fields). V14 migration relaxes the
    // NOT NULL on this column to support that two-phase shape.
    // SummaryWriter still truncates defensively at 800 chars to honour
    // the prompt's soft contract.
    @Column(name = "summary_en", columnDefinition = "TEXT")
    private String summaryEn;

    @Column(name = "importance_score", nullable = false)
    private int importanceScore;

    @Column(name = "event_type", length = 50, nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sector_tags", nullable = false)
    private List<String> sectorTags;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ticker_tags", nullable = false)
    private List<String> tickerTags;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actionable_for", nullable = false)
    private List<String> actionableFor;

    // Round-15c: removed the `updatable = false` constraint on the
    // four LLM-attribution columns below. A classifier row is written
    // first (model_used = "rule-v1", token / cost counts = 0,
    // prompt_version = 0) and may be UPDATEd in place when the LLM
    // later runs — at which point the columns must reflect the actual
    // LLM model id, real token counts, real cost, and the current
    // SummaryWriter.PROMPT_VERSION. Existing pre-15c rows are
    // unaffected; the data is unchanged, only the JPA write contract
    // is relaxed.
    @Column(name = "model_used", length = 100, nullable = false)
    private String modelUsed;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "cost_usd", nullable = false, precision = 10, scale = 8)
    private BigDecimal costUsd;

    /**
     * Version of the LLM prompt that produced this row.
     * {@link SummaryWriter#PROMPT_VERSION} is the LLM-side counter
     * (bumped on prompt body changes that should invalidate older
     * rows); a value of {@code 0} is reserved for the round-15c
     * classifier-only state and means "this row carries rule-derived
     * classification only, no LLM summary text". The retry scheduler
     * can scope re-summarisation to rows below the current LLM
     * version without nuking the whole cache; a classifier row
     * (prompt_version = 0) is also eligible for backfill so the same
     * machinery upgrades stub rows to LLM rows once budget permits.
     */
    @Column(name = "prompt_version", nullable = false)
    private short promptVersion;

    @CreationTimestamp
    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt;

    /**
     * Sentinel {@code model_used} value for rows written by the
     * round-15c rule-based classifier rather than the LLM. Lets
     * audit / analytics queries split rule rows from LLM rows
     * without parsing the prompt_version field.
     */
    public static final String RULE_BASED_MODEL_ID = "rule-v1";

    /**
     * Sentinel {@code prompt_version} value for rule-based rows.
     * Real LLM prompts use {@link SummaryWriter#PROMPT_VERSION} which
     * starts at 1, so 0 is unambiguous. The retry scheduler can pick
     * these up as "LLM backfill candidates" once budget permits.
     */
    public static final short RULE_BASED_PROMPT_VERSION = 0;

    protected DisclosureSummary() {
    }

    public DisclosureSummary(
            String rcptNo,
            String summaryEn,
            int importanceScore,
            String eventType,
            List<String> sectorTags,
            List<String> tickerTags,
            List<String> actionableFor,
            String modelUsed,
            int inputTokens,
            int outputTokens,
            BigDecimal costUsd,
            short promptVersion
    ) {
        this.rcptNo = rcptNo;
        this.summaryEn = summaryEn;
        this.importanceScore = importanceScore;
        this.eventType = eventType;
        this.sectorTags = sectorTags;
        this.tickerTags = tickerTags;
        this.actionableFor = actionableFor;
        this.modelUsed = modelUsed;
        this.promptVersion = promptVersion;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.costUsd = costUsd;
    }

    /**
     * Builds a "classifier-only" row — every classification field
     * populated by {@link DisclosureClassifier}, no LLM summary text
     * yet. Token counts and cost are zero, model id is
     * {@link #RULE_BASED_MODEL_ID}, prompt version is
     * {@link #RULE_BASED_PROMPT_VERSION}. The free
     * {@code /v1/disclosures/recent} endpoint surfaces every field
     * except {@code summaryEn}; the first paid call for this
     * {@code rcptNo} triggers an LLM run that overlays the LLM
     * fields via {@link #overlayLlmSummary}.
     */
    public static DisclosureSummary fromClassification(
            String rcptNo,
            DisclosureClassifier.Classification c
    ) {
        return new DisclosureSummary(
                rcptNo,
                null, // summary_en deliberately blank until the LLM runs
                c.importanceScore(),
                c.eventType(),
                c.sectorTags(),
                c.tickerTags(),
                c.actionableFor(),
                RULE_BASED_MODEL_ID,
                0, 0,
                BigDecimal.ZERO.setScale(8),
                RULE_BASED_PROMPT_VERSION
        );
    }

    /**
     * Overlays the LLM run's output on top of an existing row —
     * typically a classifier row whose {@code summaryEn} was blank,
     * but also re-summarisation of an older LLM row that wants its
     * fields refreshed. Mutates in place; the caller persists via
     * {@link DisclosureSummaryRepository#save}.
     */
    public void overlayLlmSummary(
            String summaryEn,
            int importanceScore,
            String eventType,
            List<String> sectorTags,
            List<String> tickerTags,
            List<String> actionableFor,
            String modelUsed,
            int inputTokens,
            int outputTokens,
            BigDecimal costUsd,
            short promptVersion
    ) {
        this.summaryEn = summaryEn;
        this.importanceScore = importanceScore;
        this.eventType = eventType;
        this.sectorTags = sectorTags;
        this.tickerTags = tickerTags;
        this.actionableFor = actionableFor;
        this.modelUsed = modelUsed;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.costUsd = costUsd;
        this.promptVersion = promptVersion;
    }

    /** True iff the row currently has the paid LLM-generated English
     *  summary text. False for round-15c classifier-only rows.
     *  Drives {@link SummaryService#summarize}'s "should I run the
     *  LLM?" decision. */
    public boolean hasLlmSummary() {
        return summaryEn != null && !summaryEn.isBlank();
    }

    public String getRcptNo() {
        return rcptNo;
    }

    public String getSummaryEn() {
        return summaryEn;
    }

    public int getImportanceScore() {
        return importanceScore;
    }

    public String getEventType() {
        return eventType;
    }

    public List<String> getSectorTags() {
        return sectorTags;
    }

    public List<String> getTickerTags() {
        return tickerTags;
    }

    public List<String> getActionableFor() {
        return actionableFor;
    }

    public String getModelUsed() {
        return modelUsed;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public short getPromptVersion() {
        return promptVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DisclosureSummary other)) return false;
        return Objects.equals(rcptNo, other.rcptNo);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(rcptNo);
    }
}
