package com.dartintel.api.summarization;

import com.dartintel.api.ingestion.DartClient;
import com.dartintel.api.ingestion.DartDocumentParser;
import com.dartintel.api.ingestion.Disclosure;
import com.dartintel.api.ingestion.DisclosureRepository;
import com.dartintel.api.summarization.classifier.ComplexityClassifier;
import com.dartintel.api.summarization.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;

/**
 * Generates AI summaries for DART disclosures.
 *
 * <h3>Single-flight invariant (P1 TOCTOU defence)</h3>
 *
 * Multiple consumers (the standing {@link
 * com.dartintel.api.summarization.job.SummaryJobConsumer}, the retry
 * scheduler, an ad-hoc backfill) can race on the same {@code rcptNo}:
 * each one runs {@link SummaryWriter#summaryExists} as its own
 * read-only transaction, both see "no summary yet", both call the
 * LLM, both write — duplicating the LLM cost and risking unique
 * constraint violations on the second write.
 *
 * <p>A Redis {@code SETNX} per-rcptNo lock around the LLM call shuts
 * the race: only one consumer can hold the lock for a given
 * {@code rcptNo} at a time, and the lock has a TTL that exceeds the
 * configured Gemini read timeout so the lock never outlives the
 * useful work it protects. Subsequent consumers see the lock, log
 * skip, and exit cleanly without paying for a redundant LLM run.
 *
 * <p>The audit-success short-circuit additionally protects against
 * the partial-write recovery case: if a previous run committed the
 * audit row but the summary insert failed (a constraint violation or
 * Postgres outage between the two REQUIRES_NEW transactions), the
 * retry scheduler would re-enqueue the rcpt_no. Without the
 * short-circuit, the LLM would be called again. With it, the second
 * attempt sees the audit success and returns — letting an operator
 * heal the missing summary row from the audit data offline.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SummaryService {

    /** Redis key prefix for the per-rcptNo summarisation single-flight lock. */
    static final String LOCK_PREFIX = "summary_inflight:";
    /** Lock TTL — must exceed the configured Gemini read timeout. */
    static final Duration LOCK_TTL = Duration.ofMinutes(2);

    private final DisclosureRepository disclosureRepository;
    private final SummaryWriter writer;
    private final ComplexityClassifier classifier;
    private final LlmClient llmClient;
    private final StringRedisTemplate redisTemplate;
    private final DartClient dartClient;
    private final DartDocumentParser documentParser;

    public void summarize(String rcptNo) {
        Optional<Disclosure> opt = disclosureRepository.findById(rcptNo);
        if (opt.isEmpty()) {
            log.warn("summarize: unknown rcpt_no={}", rcptNo);
            return;
        }
        if (writer.summaryExists(rcptNo)) {
            log.debug("summarize: skip {} (already summarized)", rcptNo);
            return;
        }
        // Audit-success short-circuit — see class javadoc for the
        // partial-write recovery rationale.
        if (writer.auditSuccessExists(rcptNo)) {
            log.warn("summarize: skip {} — audit success exists but summary row missing; "
                    + "manual heal required (check llm_audit for stored cost / model)", rcptNo);
            return;
        }
        // Single-flight lock — only one consumer per rcpt_no may invoke
        // the LLM at any time. A second consumer that loses the SETNX
        // race exits without re-paying. The TTL guards against a stuck
        // consumer holding the lock forever.
        String lockKey = LOCK_PREFIX + rcptNo;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("summarize: skip {} (another consumer is in-flight)", rcptNo);
            return;
        }

        try {
            doSummarize(rcptNo, opt.get());
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    private void doSummarize(String rcptNo, Disclosure d) {
        // Body fetch + parse runs OUTSIDE any DB transaction so a slow
        // DART download or a tripped dart-document breaker doesn't pin
        // a Hikari connection. The body is persisted via a separate
        // small REQUIRES_NEW transaction in saveBodyIfMissing(...) so
        // a later LLM failure doesn't erase the body cache row.
        String body = ensureBody(d);

        DisclosureContext ctx = new DisclosureContext(
                d.getRcptNo(),
                d.getCorpCode(),
                d.getCorpName(),
                d.getCorpNameEng(),
                d.getReportNm(),
                d.getRceptDt(),
                d.getRm(),
                body
        );
        Complexity complexity = classifier.classify(d.getReportNm());
        log.debug("summarize: rcpt_no={} complexity={} hasBody={} bodyChars={}",
                rcptNo, complexity, ctx.hasBody(), body == null ? 0 : body.length());

        String promptHash = sha256(buildPromptDigest(ctx));
        long start = System.currentTimeMillis();
        try {
            SummaryEnvelope env = llmClient.summarize(ctx);
            // Audit row commits first (REQUIRES_NEW) so a downstream
            // summary-insert failure never erases the record that the
            // LLM provider was paid.
            writer.recordAuditSuccess(rcptNo, env, promptHash);
            writer.recordSummary(rcptNo, env);
            log.info("summarize: rcpt_no={} cost=${} latency={}ms hasBody={}",
                    rcptNo, env.costUsd(), env.latencyMs(), ctx.hasBody());
        } catch (Exception e) {
            int elapsedMs = (int) (System.currentTimeMillis() - start);
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            writer.recordAuditFailure(rcptNo, llmClient.modelId(), promptHash, elapsedMs, errMsg);
            log.error("summarize: rcpt_no={} failed in {}ms: {}", rcptNo, elapsedMs, errMsg);
        }
    }

    /**
     * Return the body text for the given disclosure, fetching from
     * DART {@code /document.xml} on first call and caching to
     * {@code disclosure.body}. Returns {@code null} when the fetch
     * fails — the LLM then falls back to title-only summarisation
     * with the existing prompt branch.
     *
     * <p>Failure modes that are tolerated and surface as a null body:
     * <ul>
     *   <li>404 — filing not yet finalised by DART (very fresh
     *       filings show up in /list.json before /document.xml is
     *       ready).</li>
     *   <li>Open dart-document circuit breaker — body endpoint is
     *       in burst-failure mode.</li>
     *   <li>Body exceeds the configured byte cap.</li>
     *   <li>Parser failure — corrupt ZIP, unrecognised charset.</li>
     * </ul>
     *
     * <p>None of these should kill the summary. The agent paid for a
     * summary; a degraded title-only one is still a usable answer.
     */
    String ensureBody(Disclosure d) {
        if (d.getBody() != null && !d.getBody().isBlank()) {
            return d.getBody();
        }
        try {
            byte[] zipBytes = dartClient.fetchDocument(d.getRcptNo());
            String parsed = documentParser.parse(zipBytes);
            if (parsed == null || parsed.isBlank()) {
                log.debug("ensureBody: rcpt_no={} parsed body is empty, skipping persist",
                        d.getRcptNo());
                return null;
            }
            saveBodyIfMissing(d.getRcptNo(), parsed);
            return parsed;
        } catch (Exception e) {
            // All body-fetch failures degrade gracefully to title-only
            // summarisation — no rethrow. Log at INFO not WARN because
            // the dominant case (DART hasn't published the body yet)
            // is normal.
            log.info("ensureBody: rcpt_no={} body fetch failed, falling back to title-only: {}",
                    d.getRcptNo(), e.getMessage());
            return null;
        }
    }

    /**
     * Persist the parsed body in its own small transaction so the
     * body row commits even if the subsequent LLM call fails. A
     * second paid call for the same rcpt_no then short-circuits the
     * body fetch via {@link Disclosure#getBody()} — already in the
     * cache after the first attempt's body fetch, even though the
     * first attempt's LLM run errored out.
     */
    @Transactional
    public void saveBodyIfMissing(String rcptNo, String body) {
        disclosureRepository.findById(rcptNo).ifPresent(row -> {
            if (row.getBody() == null || row.getBody().isBlank()) {
                row.setBody(body);
                disclosureRepository.save(row);
            }
        });
    }

    private static String buildPromptDigest(DisclosureContext c) {
        return c.rcptNo() + "|" + c.corpName() + "|" + c.reportNm() + "|" + c.rceptDt();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
