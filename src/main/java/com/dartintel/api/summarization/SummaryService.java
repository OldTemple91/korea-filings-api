package com.dartintel.api.summarization;

import com.dartintel.api.ingestion.Disclosure;
import com.dartintel.api.ingestion.DisclosureRepository;
import com.dartintel.api.summarization.classifier.ComplexityClassifier;
import com.dartintel.api.summarization.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
        DisclosureContext ctx = new DisclosureContext(
                d.getRcptNo(),
                d.getCorpCode(),
                d.getCorpName(),
                d.getCorpNameEng(),
                d.getReportNm(),
                d.getRceptDt(),
                d.getRm()
        );
        Complexity complexity = classifier.classify(d.getReportNm());
        // Day 4: route every complexity to Flash-Lite. Flash + extended-reasoning land Week 2+.
        log.debug("summarize: rcpt_no={} complexity={}", rcptNo, complexity);

        String promptHash = sha256(buildPromptDigest(ctx));
        long start = System.currentTimeMillis();
        try {
            SummaryEnvelope env = llmClient.summarize(ctx);
            // Audit row commits first (REQUIRES_NEW) so a downstream
            // summary-insert failure never erases the record that the
            // LLM provider was paid.
            writer.recordAuditSuccess(rcptNo, env, promptHash);
            writer.recordSummary(rcptNo, env);
            log.info("summarize: rcpt_no={} cost=${} latency={}ms",
                    rcptNo, env.costUsd(), env.latencyMs());
        } catch (Exception e) {
            int elapsedMs = (int) (System.currentTimeMillis() - start);
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            writer.recordAuditFailure(rcptNo, llmClient.modelId(), promptHash, elapsedMs, errMsg);
            log.error("summarize: rcpt_no={} failed in {}ms: {}", rcptNo, elapsedMs, errMsg);
        }
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
