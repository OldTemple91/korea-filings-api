package com.dartintel.api.observability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistent twin of one {@code REQ_AUDIT} log line. Written by
 * {@link RequestAuditPersister} when {@code audit.requests.persist} is
 * enabled, queried by hand or via {@code docs/ANALYTICS.md} for
 * funnel / KPI / cohort analysis.
 *
 * <p>Design note: rows are immutable after insert. There is no setter
 * that would allow mutation post-insert; the only writes are bulk
 * INSERTs from the persister and the daily DELETE pruning the > 90 day
 * tail. Keeping the row read-only avoids surprises around cohort
 * snapshots — a row never retroactively changes its status or
 * payment-presence flags, even if we later evolve the schema.
 */
@Entity
@Table(name = "request_audit")
public class RequestAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ts", nullable = false)
    private Instant ts;

    @Column(name = "method", nullable = false, length = 8)
    private String method;

    @Column(name = "path", nullable = false, length = 256)
    private String path;

    @Column(name = "status", nullable = false)
    private int status;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "user_agent", length = 256)
    private String userAgent;

    @Column(name = "query_keys", length = 512)
    private String queryKeys;

    @Column(name = "body_bytes")
    private Long bodyBytes;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "has_x_payment", nullable = false)
    private boolean hasXPayment;

    @Column(name = "has_payment_sig", nullable = false)
    private boolean hasPaymentSig;

    protected RequestAudit() {
        // for JPA
    }

    private RequestAudit(Builder b) {
        this.ts = b.ts;
        this.method = b.method;
        this.path = b.path;
        this.status = b.status;
        this.ip = b.ip;
        this.userAgent = b.userAgent;
        this.queryKeys = b.queryKeys;
        this.bodyBytes = b.bodyBytes;
        this.contentType = b.contentType;
        this.hasXPayment = b.hasXPayment;
        this.hasPaymentSig = b.hasPaymentSig;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getId() {
        return id;
    }

    public Instant getTs() {
        return ts;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public int getStatus() {
        return status;
    }

    public String getIp() {
        return ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getQueryKeys() {
        return queryKeys;
    }

    public Long getBodyBytes() {
        return bodyBytes;
    }

    public String getContentType() {
        return contentType;
    }

    public boolean isHasXPayment() {
        return hasXPayment;
    }

    public boolean isHasPaymentSig() {
        return hasPaymentSig;
    }

    public static final class Builder {
        private Instant ts = Instant.now();
        private String method;
        private String path;
        private int status;
        private String ip;
        private String userAgent;
        private String queryKeys;
        private Long bodyBytes;
        private String contentType;
        private boolean hasXPayment;
        private boolean hasPaymentSig;

        public Builder ts(Instant v) { this.ts = v; return this; }
        public Builder method(String v) { this.method = v; return this; }
        public Builder path(String v) { this.path = v; return this; }
        public Builder status(int v) { this.status = v; return this; }
        public Builder ip(String v) { this.ip = v; return this; }
        public Builder userAgent(String v) { this.userAgent = v; return this; }
        public Builder queryKeys(String v) { this.queryKeys = v; return this; }
        public Builder bodyBytes(Long v) { this.bodyBytes = v; return this; }
        public Builder contentType(String v) { this.contentType = v; return this; }
        public Builder hasXPayment(boolean v) { this.hasXPayment = v; return this; }
        public Builder hasPaymentSig(boolean v) { this.hasPaymentSig = v; return this; }

        public RequestAudit build() {
            return new RequestAudit(this);
        }
    }
}
