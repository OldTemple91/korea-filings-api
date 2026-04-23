package com.dartintel.api.summarization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "llm_audit")
public class LlmAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "rcpt_no", length = 14, nullable = false, updatable = false)
    private String rcptNo;

    @Column(name = "model", length = 100, nullable = false, updatable = false)
    private String model;

    @Column(name = "prompt_hash", length = 64, nullable = false, updatable = false)
    private String promptHash;

    @Column(name = "input_tokens", updatable = false)
    private Integer inputTokens;

    @Column(name = "output_tokens", updatable = false)
    private Integer outputTokens;

    @Column(name = "latency_ms", nullable = false, updatable = false)
    private int latencyMs;

    @Column(name = "cost_usd", precision = 10, scale = 8, updatable = false)
    private BigDecimal costUsd;

    @Column(name = "success", nullable = false, updatable = false)
    private boolean success;

    @Column(name = "error_message", updatable = false, columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LlmAudit() {
    }

    private LlmAudit(
            String rcptNo,
            String model,
            String promptHash,
            Integer inputTokens,
            Integer outputTokens,
            int latencyMs,
            BigDecimal costUsd,
            boolean success,
            String errorMessage
    ) {
        this.rcptNo = rcptNo;
        this.model = model;
        this.promptHash = promptHash;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.latencyMs = latencyMs;
        this.costUsd = costUsd;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public static LlmAudit success(
            String rcptNo,
            String model,
            String promptHash,
            int inputTokens,
            int outputTokens,
            int latencyMs,
            BigDecimal costUsd
    ) {
        return new LlmAudit(rcptNo, model, promptHash,
                inputTokens, outputTokens, latencyMs, costUsd, true, null);
    }

    public static LlmAudit failure(
            String rcptNo,
            String model,
            String promptHash,
            int latencyMs,
            String errorMessage
    ) {
        return new LlmAudit(rcptNo, model, promptHash,
                null, null, latencyMs, null, false, errorMessage);
    }

    public Long getId() {
        return id;
    }

    public String getRcptNo() {
        return rcptNo;
    }

    public String getModel() {
        return model;
    }

    public String getPromptHash() {
        return promptHash;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public int getLatencyMs() {
        return latencyMs;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
