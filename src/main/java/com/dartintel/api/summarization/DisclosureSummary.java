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

    @Column(name = "summary_en", length = 500, nullable = false)
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

    @Column(name = "model_used", length = 100, nullable = false, updatable = false)
    private String modelUsed;

    @Column(name = "input_tokens", nullable = false, updatable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false, updatable = false)
    private int outputTokens;

    @Column(name = "cost_usd", nullable = false, updatable = false, precision = 10, scale = 8)
    private BigDecimal costUsd;

    @CreationTimestamp
    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt;

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
            BigDecimal costUsd
    ) {
        this.rcptNo = rcptNo;
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
