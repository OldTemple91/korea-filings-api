package com.dartintel.api.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "disclosure")
public class Disclosure {

    @Id
    @Column(name = "rcpt_no", length = 14, nullable = false, updatable = false)
    private String rcptNo;

    @Column(name = "corp_code", length = 8, nullable = false)
    private String corpCode;

    @Column(name = "corp_name", length = 200, nullable = false)
    private String corpName;

    @Column(name = "corp_name_eng", length = 200)
    private String corpNameEng;

    @Column(name = "report_nm", length = 500, nullable = false)
    private String reportNm;

    @Column(name = "flr_nm", length = 200, nullable = false)
    private String flrNm;

    @Column(name = "rcept_dt", nullable = false)
    private LocalDate rceptDt;

    @Column(name = "rm", length = 20)
    private String rm;

    /**
     * Six-digit KRX ticker, denormalised from the {@code company}
     * table at ingestion time so the by-ticker query path is a single
     * indexed table scan. {@code NULL} for filers without a stock
     * code (delisted entities, foreign filers, non-corp filers) — the
     * by-ticker endpoint filters those out.
     */
    @Column(name = "ticker", length = 6)
    private String ticker;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Disclosure() {
    }

    public Disclosure(
            String rcptNo,
            String corpCode,
            String corpName,
            String corpNameEng,
            String reportNm,
            String flrNm,
            LocalDate rceptDt,
            String rm,
            String ticker
    ) {
        this.rcptNo = rcptNo;
        this.corpCode = corpCode;
        this.corpName = corpName;
        this.corpNameEng = corpNameEng;
        this.reportNm = reportNm;
        this.flrNm = flrNm;
        this.rceptDt = rceptDt;
        this.rm = rm;
        this.ticker = ticker;
    }

    public String getRcptNo() {
        return rcptNo;
    }

    public String getCorpCode() {
        return corpCode;
    }

    public String getCorpName() {
        return corpName;
    }

    public String getCorpNameEng() {
        return corpNameEng;
    }

    public String getReportNm() {
        return reportNm;
    }

    public String getFlrNm() {
        return flrNm;
    }

    public LocalDate getRceptDt() {
        return rceptDt;
    }

    public String getRm() {
        return rm;
    }

    public String getTicker() {
        return ticker;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Disclosure other)) return false;
        return Objects.equals(rcptNo, other.rcptNo);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(rcptNo);
    }
}
