package com.dartintel.api.ingestion;

import com.dartintel.api.company.CompanyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Polls the DART {@code /list.json} endpoint and persists new
 * disclosures.
 *
 * <h3>Transaction boundary</h3>
 *
 * The DART HTTP fetch runs OUTSIDE any DB transaction so a slow DART
 * response (or an open Resilience4j circuit breaker) does not pin a
 * Hikari connection for the duration of the call. The DB writes are
 * scoped to {@link #persistBatch} which opens its own transaction and
 * runs against rows we already have in memory. Without this split,
 * a DART read timing out at 10 s could drain the connection pool.
 *
 * <h3>Cursor write timing</h3>
 *
 * The Redis cursor write only happens after the DB transaction
 * commits. Otherwise a Postgres failure mid-batch would roll back the
 * disclosure inserts but leave the cursor advanced — silently losing
 * filings forever. The {@code persistBatch} return value carries the
 * latest filing date out so the caller (no longer transactional)
 * can write it post-commit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "dart.polling.enabled", havingValue = "true", matchIfMissing = true)
public class DartPollingScheduler {

    static final String CURSOR_KEY = "dart_last_rcept_dt";
    static final String DART_STATUS_OK = "000";
    static final String DART_STATUS_NO_DATA = "013";
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final DartClient dartClient;
    private final DisclosureRepository disclosureRepository;
    private final StringRedisTemplate redisTemplate;
    private final DartProperties props;
    private final CompanyService companyService;

    @Scheduled(fixedDelayString = "${dart.polling.interval-ms:30000}")
    public void poll() {
        LocalDate cursor = readCursor()
                .orElseGet(() -> LocalDate.now().minusDays(props.polling().initialCursorDaysBack()));
        log.debug("Polling DART since {}", cursor);

        DartListResponse response;
        try {
            // External HTTP call — run BEFORE opening any transaction
            // so a slow DART response cannot starve the Hikari pool.
            response = dartClient.fetchList(cursor);
        } catch (Exception e) {
            log.error("DART polling call failed: {}", e.getMessage());
            return;
        }

        if (DART_STATUS_NO_DATA.equals(response.status())) {
            return;
        }
        if (!DART_STATUS_OK.equals(response.status())) {
            log.error("DART API error status={} message={}", response.status(), response.message());
            return;
        }
        if (response.list() == null || response.list().isEmpty()) {
            return;
        }

        BatchResult batch = persistBatch(response.list(), cursor);

        if (batch.newCount() > 0) {
            log.info("Persisted {} new DART filings (latest rcept_dt {})",
                    batch.newCount(), batch.maxDate());
            // Cursor write happens AFTER the persist transaction
            // commits. If persistBatch threw, we never reach here, so
            // the cursor stays at its old value and the next poll
            // re-fetches the missed window.
            writeCursor(batch.maxDate());
        }
    }

    /**
     * Persists the new filings inside a single transaction and returns
     * the count plus the highest {@code rceptDt} seen. The whole
     * method runs in one Hikari connection because it is short
     * (in-memory iteration over a bounded list, no further HTTP
     * calls).
     */
    @Transactional
    public BatchResult persistBatch(java.util.List<DartListResponse.DartFiling> filings, LocalDate cursor) {
        int newCount = 0;
        LocalDate maxDate = cursor;
        for (DartListResponse.DartFiling filing : filings) {
            if (disclosureRepository.existsByRcptNo(filing.rcptNo())) {
                continue;
            }
            LocalDate filingDate = LocalDate.parse(filing.rceptDt(), YYYYMMDD);
            // Resolve the corp_code → ticker mapping at ingestion time so
            // by-ticker queries don't need a join. Returns null for
            // unlisted filers (delisted, foreign, non-corp) — the
            // by-ticker endpoint then transparently filters them out.
            String ticker = companyService.findByCorpCode(filing.corpCode())
                    .map(c -> c.getTicker())
                    .orElse(null);
            disclosureRepository.save(new Disclosure(
                    filing.rcptNo(),
                    filing.corpCode(),
                    filing.corpName(),
                    null,
                    filing.reportNm(),
                    filing.flrNm(),
                    filingDate,
                    filing.rm(),
                    ticker
            ));
            // v1.1 lazy pivot: ingestion is metadata-only. Summary
            // generation now happens inside DisclosuresController on
            // the first paid call for each rcpt_no, with body fetch
            // + LLM run guarded by a single-flight Redis lock. The
            // SummaryJobQueue is no longer pushed to from here — see
            // ARCHITECTURE.md "Lazy summarisation" for the data flow.
            newCount++;
            if (filingDate.isAfter(maxDate)) {
                maxDate = filingDate;
            }
        }
        return new BatchResult(newCount, maxDate);
    }

    private Optional<LocalDate> readCursor() {
        String value = redisTemplate.opsForValue().get(CURSOR_KEY);
        return value == null ? Optional.empty() : Optional.of(LocalDate.parse(value, YYYYMMDD));
    }

    private void writeCursor(LocalDate date) {
        redisTemplate.opsForValue().set(CURSOR_KEY, date.format(YYYYMMDD));
    }

    /** Outcome of a single persist transaction — used to advance the cursor post-commit. */
    public record BatchResult(int newCount, LocalDate maxDate) {
    }
}
