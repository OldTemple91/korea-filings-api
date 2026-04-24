package com.dartintel.api.ingestion;

import com.dartintel.api.summarization.job.SummaryJobQueue;
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
    private final SummaryJobQueue summaryJobQueue;

    @Scheduled(fixedDelayString = "${dart.polling.interval-ms:30000}")
    @Transactional
    public void poll() {
        LocalDate cursor = readCursor()
                .orElseGet(() -> LocalDate.now().minusDays(props.polling().initialCursorDaysBack()));
        log.debug("Polling DART since {}", cursor);

        DartListResponse response;
        try {
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

        int newCount = 0;
        LocalDate maxDate = cursor;
        for (DartListResponse.DartFiling filing : response.list()) {
            if (disclosureRepository.existsByRcptNo(filing.rcptNo())) {
                continue;
            }
            LocalDate filingDate = LocalDate.parse(filing.rceptDt(), YYYYMMDD);
            disclosureRepository.save(new Disclosure(
                    filing.rcptNo(),
                    filing.corpCode(),
                    filing.corpName(),
                    null,
                    filing.reportNm(),
                    filing.flrNm(),
                    filingDate,
                    filing.rm()
            ));
            // Hand the newly persisted filing off to the summarisation pipeline.
            // Redis outages here are tolerated: the filing is already in the DB
            // and a later backfill run will pick it up.
            try {
                summaryJobQueue.push(filing.rcptNo());
            } catch (Exception queueError) {
                log.warn("Enqueue to summary_job_queue failed for rcpt_no={}: {}",
                        filing.rcptNo(), queueError.getMessage());
            }
            newCount++;
            if (filingDate.isAfter(maxDate)) {
                maxDate = filingDate;
            }
        }

        if (newCount > 0) {
            log.info("Persisted {} new DART filings (latest rcept_dt {})", newCount, maxDate);
            writeCursor(maxDate);
        }
    }

    private Optional<LocalDate> readCursor() {
        String value = redisTemplate.opsForValue().get(CURSOR_KEY);
        return value == null ? Optional.empty() : Optional.of(LocalDate.parse(value, YYYYMMDD));
    }

    private void writeCursor(LocalDate date) {
        redisTemplate.opsForValue().set(CURSOR_KEY, date.format(YYYYMMDD));
    }
}
