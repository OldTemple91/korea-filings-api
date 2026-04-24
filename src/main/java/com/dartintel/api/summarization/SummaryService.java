package com.dartintel.api.summarization;

import com.dartintel.api.ingestion.Disclosure;
import com.dartintel.api.ingestion.DisclosureRepository;
import com.dartintel.api.summarization.classifier.ComplexityClassifier;
import com.dartintel.api.summarization.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SummaryService {

    private final DisclosureRepository disclosureRepository;
    private final SummaryWriter writer;
    private final ComplexityClassifier classifier;
    private final LlmClient llmClient;

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

        Disclosure d = opt.get();
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
            // Audit row commits first so a downstream summary-insert failure
            // never erases the record that we paid the LLM provider.
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
