package com.dartintel.api.company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A Korean listed company synced from DART's corpCode.xml dump.
 *
 * <p>The primary identifier exposed to API callers is the six-digit
 * {@code ticker} (KRX stock code) — this is what humans, AI agents,
 * news articles, and our public {@code /v1/companies} endpoint search
 * by. The {@code corpCode} is DART's eight-digit internal id and the
 * join key we use against {@link com.dartintel.api.ingestion.Disclosure}.
 *
 * <p>We only persist entries whose {@code stock_code} field in the
 * DART corpCode dump is non-empty — i.e. companies actually listed on
 * KOSPI / KOSDAQ / KONEX. Delisted or non-listed filers are skipped
 * because the by-ticker endpoint can't address them anyway.
 */
@Entity
@Table(name = "company")
public class Company {

    @Id
    @Column(name = "ticker", length = 7, nullable = false, updatable = false)
    private String ticker;

    @Column(name = "corp_code", length = 8, nullable = false, unique = true)
    private String corpCode;

    @Column(name = "name_kr", length = 200, nullable = false)
    private String nameKr;

    @Column(name = "name_en", length = 300)
    private String nameEn;

    @Column(name = "market", length = 20)
    private String market;

    @Column(name = "last_modified_at", nullable = false)
    private LocalDate lastModifiedAt;

    @CreationTimestamp
    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Company() {
    }

    public Company(
            String ticker,
            String corpCode,
            String nameKr,
            String nameEn,
            String market,
            LocalDate lastModifiedAt
    ) {
        this.ticker = ticker;
        this.corpCode = corpCode;
        this.nameKr = nameKr;
        this.nameEn = nameEn;
        this.market = market;
        this.lastModifiedAt = lastModifiedAt;
    }

    /**
     * Apply the latest DART metadata to an existing row. We never
     * change the primary key or the corp_code — those are forever once
     * a company is listed — but the names and market do drift over
     * time (rebrandings, market migrations).
     */
    public void update(String nameKr, String nameEn, String market, LocalDate lastModifiedAt) {
        this.nameKr = nameKr;
        this.nameEn = nameEn;
        this.market = market;
        this.lastModifiedAt = lastModifiedAt;
    }

    public String getTicker() {
        return ticker;
    }

    public String getCorpCode() {
        return corpCode;
    }

    public String getNameKr() {
        return nameKr;
    }

    public String getNameEn() {
        return nameEn;
    }

    public String getMarket() {
        return market;
    }

    public LocalDate getLastModifiedAt() {
        return lastModifiedAt;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Company other)) return false;
        return Objects.equals(ticker, other.ticker);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(ticker);
    }
}
