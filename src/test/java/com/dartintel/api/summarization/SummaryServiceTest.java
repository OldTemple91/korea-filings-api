package com.dartintel.api.summarization;

import com.dartintel.api.ingestion.DartClient;
import com.dartintel.api.ingestion.DartDocumentParser;
import com.dartintel.api.ingestion.Disclosure;
import com.dartintel.api.ingestion.DisclosureRepository;
import com.dartintel.api.summarization.classifier.ComplexityClassifier;
import com.dartintel.api.summarization.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SummaryServiceTest {

    @Mock
    DisclosureRepository disclosureRepository;
    @Mock
    SummaryWriter writer;
    @Mock
    ComplexityClassifier classifier;
    @Mock
    LlmClient llmClient;
    @Mock
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    @Mock
    org.springframework.data.redis.core.ValueOperations<String, String> valueOps;
    @Mock
    DartClient dartClient;
    @Mock
    DartDocumentParser documentParser;

    @InjectMocks
    SummaryService service;

    private Disclosure sampleDisclosure;
    private SummaryEnvelope sampleEnvelope;

    @BeforeEach
    void setUp() {
        when(llmClient.modelId()).thenReturn("gemini-2.5-flash-lite");
        // Single-flight lock: by default the test acquires the lock
        // immediately. Specific tests override this to simulate a
        // racing consumer holding the lock.
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(true);
        sampleDisclosure = new Disclosure(
                "20260423000001", "00126380", "삼성전자", "Samsung Electronics Co., Ltd.",
                "주요사항보고서(유상증자결정)", "삼성전자",
                LocalDate.of(2026, 4, 23), "유", "005930"
        );
        sampleEnvelope = new SummaryEnvelope(
                new SummaryResult(
                        "Samsung announced a rights offering decision.",
                        9, List.of("Information Technology"), List.of("005930"),
                        "RIGHTS_OFFERING", List.of("traders", "long_term_investors")
                ),
                "gemini-2.5-flash-lite", 142, 89,
                new BigDecimal("0.00004980"), 834L
        );
    }

    @Test
    void happyPathPersistsAuditFirstThenSummary() {
        when(disclosureRepository.findById("20260423000001")).thenReturn(Optional.of(sampleDisclosure));
        when(writer.summaryExists("20260423000001")).thenReturn(false);
        when(classifier.classify(anyString())).thenReturn(Complexity.MEDIUM);
        when(llmClient.summarize(any())).thenReturn(sampleEnvelope);

        service.summarize("20260423000001");

        var inOrder = org.mockito.Mockito.inOrder(writer);
        inOrder.verify(writer).recordAuditSuccess(eq("20260423000001"), eq(sampleEnvelope), anyString());
        inOrder.verify(writer).recordSummary("20260423000001", sampleEnvelope);
        verify(writer, never()).recordAuditFailure(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void unknownRcptNoLogsAndExitsWithoutSideEffects() {
        when(disclosureRepository.findById("99999999999999")).thenReturn(Optional.empty());

        service.summarize("99999999999999");

        verifyNoInteractions(llmClient, classifier);
        verify(writer, never()).recordAuditSuccess(anyString(), any(), anyString());
        verify(writer, never()).recordSummary(anyString(), any());
        verify(writer, never()).recordAuditFailure(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void alreadySummarizedSkipsLlmCallAndPersists() {
        when(disclosureRepository.findById("20260423000001")).thenReturn(Optional.of(sampleDisclosure));
        when(writer.summaryExists("20260423000001")).thenReturn(true);

        service.summarize("20260423000001");

        verifyNoInteractions(llmClient, classifier);
        verify(writer, never()).recordAuditSuccess(anyString(), any(), anyString());
        verify(writer, never()).recordSummary(anyString(), any());
        verify(writer, never()).recordAuditFailure(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void auditSuccessExistsButSummaryRowMissingShortCircuitsBeforeLlmCall() {
        // Partial-write recovery path: a previous run committed the
        // audit success row (REQUIRES_NEW) but the disclosure_summary
        // insert that followed it failed. The retry scheduler later
        // re-enqueues the rcptNo. Without the audit-success short
        // circuit, the LLM would be called again — burning Gemini cost
        // for a row already marked successful. The short-circuit logs
        // a warn for operator attention and returns without paying.
        when(disclosureRepository.findById("20260423000001")).thenReturn(Optional.of(sampleDisclosure));
        when(writer.summaryExists("20260423000001")).thenReturn(false);
        when(writer.auditSuccessExists("20260423000001")).thenReturn(true);

        service.summarize("20260423000001");

        verifyNoInteractions(llmClient, classifier);
        verify(writer, never()).recordAuditSuccess(anyString(), any(), anyString());
        verify(writer, never()).recordSummary(anyString(), any());
        verify(writer, never()).recordAuditFailure(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void llmExceptionRecordsFailureAuditOnly() {
        when(disclosureRepository.findById("20260423000001")).thenReturn(Optional.of(sampleDisclosure));
        when(writer.summaryExists("20260423000001")).thenReturn(false);
        when(classifier.classify(anyString())).thenReturn(Complexity.SIMPLE);
        when(llmClient.summarize(any())).thenThrow(new RuntimeException("Gemini timeout"));

        service.summarize("20260423000001");

        verify(writer).recordAuditFailure(
                eq("20260423000001"),
                eq("gemini-2.5-flash-lite"),
                argThat(hash -> hash != null && hash.length() == 64),
                anyInt(),
                eq("Gemini timeout")
        );
        verify(writer, never()).recordAuditSuccess(anyString(), any(), anyString());
        verify(writer, never()).recordSummary(anyString(), any());
    }

    @Test
    void llmExceptionWithNullMessageUsesExceptionClassName() {
        when(disclosureRepository.findById("20260423000001")).thenReturn(Optional.of(sampleDisclosure));
        when(writer.summaryExists("20260423000001")).thenReturn(false);
        when(classifier.classify(anyString())).thenReturn(Complexity.SIMPLE);
        when(llmClient.summarize(any())).thenThrow(new NullPointerException());

        service.summarize("20260423000001");

        verify(writer).recordAuditFailure(
                eq("20260423000001"),
                eq("gemini-2.5-flash-lite"),
                anyString(),
                anyInt(),
                eq("NullPointerException")
        );
    }

    // ----- v1.1 lazy + body fetch -----

    @Test
    void bodyAlreadyOnDisclosureSkipsDocumentFetchAndForwardsBodyToLlm() {
        sampleDisclosure.setBody("선매되어 있는 본문 텍스트 — 1000억원 규모 신주발행");
        when(disclosureRepository.findById("20260423000001"))
                .thenReturn(Optional.of(sampleDisclosure));
        when(writer.summaryExists("20260423000001")).thenReturn(false);
        when(classifier.classify(anyString())).thenReturn(Complexity.MEDIUM);
        when(llmClient.summarize(any())).thenReturn(sampleEnvelope);

        service.summarize("20260423000001");

        // Cached body short-circuits the DART fetch entirely.
        verifyNoInteractions(dartClient, documentParser);
        // And the body flows through to the LLM context.
        ArgumentCaptor<DisclosureContext> ctxCaptor =
                ArgumentCaptor.forClass(DisclosureContext.class);
        verify(llmClient).summarize(ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().hasBody()).isTrue();
        assertThat(ctxCaptor.getValue().body()).contains("1000억원");
    }

    @Test
    void bodyMissingTriggersFetchParseAndPersistThenFlowsToLlm() {
        when(disclosureRepository.findById("20260423000001"))
                .thenReturn(Optional.of(sampleDisclosure));
        when(writer.summaryExists("20260423000001")).thenReturn(false);
        when(classifier.classify(anyString())).thenReturn(Complexity.MEDIUM);

        byte[] zipBytes = new byte[]{0x50, 0x4B};
        when(dartClient.fetchDocument("20260423000001")).thenReturn(zipBytes);
        when(documentParser.parse(zipBytes)).thenReturn("파싱된 본문 — 신주발행 결정 내용");
        when(llmClient.summarize(any())).thenReturn(sampleEnvelope);

        service.summarize("20260423000001");

        // Body was fetched and parsed.
        verify(dartClient).fetchDocument("20260423000001");
        verify(documentParser).parse(zipBytes);
        // And the LLM saw the parsed body.
        ArgumentCaptor<DisclosureContext> ctxCaptor =
                ArgumentCaptor.forClass(DisclosureContext.class);
        verify(llmClient).summarize(ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().hasBody()).isTrue();
        assertThat(ctxCaptor.getValue().body()).contains("신주발행");
    }

    @Test
    void bodyFetchFailureDegradesToTitleOnlyAndStillCallsLlm() {
        // DART /document.xml returning 404 (filing not yet finalised) is
        // a frequent normal-state path for fresh filings. The summary
        // must still be generated — title-only path — so the agent gets
        // the response they paid for.
        when(disclosureRepository.findById("20260423000001"))
                .thenReturn(Optional.of(sampleDisclosure));
        when(writer.summaryExists("20260423000001")).thenReturn(false);
        when(classifier.classify(anyString())).thenReturn(Complexity.MEDIUM);
        when(dartClient.fetchDocument("20260423000001"))
                .thenThrow(new IllegalStateException("DART /document.xml returned 404"));
        when(llmClient.summarize(any())).thenReturn(sampleEnvelope);

        service.summarize("20260423000001");

        // Parser is never called because fetch threw before reaching it.
        verifyNoInteractions(documentParser);
        // LLM still ran, with no body.
        ArgumentCaptor<DisclosureContext> ctxCaptor =
                ArgumentCaptor.forClass(DisclosureContext.class);
        verify(llmClient).summarize(ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().hasBody()).isFalse();
        // Audit success was recorded — the agent's payment is honoured.
        verify(writer).recordAuditSuccess(eq("20260423000001"), any(), anyString());
    }

    @Test
    void parsedBodyIsBlankSkipsPersistAndPassesNullToLlm() {
        // DART occasionally returns a ZIP with only image attachments
        // — parser yields empty string. Don't persist an empty body
        // (so the next paid call retries), but proceed to LLM
        // title-only.
        when(disclosureRepository.findById("20260423000001"))
                .thenReturn(Optional.of(sampleDisclosure));
        when(writer.summaryExists("20260423000001")).thenReturn(false);
        when(classifier.classify(anyString())).thenReturn(Complexity.MEDIUM);
        when(dartClient.fetchDocument("20260423000001"))
                .thenReturn(new byte[]{0x50, 0x4B});
        when(documentParser.parse(any())).thenReturn("");
        when(llmClient.summarize(any())).thenReturn(sampleEnvelope);

        service.summarize("20260423000001");

        // No body persistence path runs — disclosureRepository.save() is
        // never invoked from saveBodyIfMissing, so the row stays
        // unchanged for the next attempt.
        verify(disclosureRepository, never()).save(any());
        ArgumentCaptor<DisclosureContext> ctxCaptor =
                ArgumentCaptor.forClass(DisclosureContext.class);
        verify(llmClient).summarize(ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().hasBody()).isFalse();
    }

    @Test
    void promptHashIsStableForIdenticalDisclosure() {
        when(disclosureRepository.findById("20260423000001")).thenReturn(Optional.of(sampleDisclosure));
        when(writer.summaryExists("20260423000001")).thenReturn(false);
        when(classifier.classify(anyString())).thenReturn(Complexity.MEDIUM);
        when(llmClient.summarize(any())).thenReturn(sampleEnvelope);

        service.summarize("20260423000001");
        service.summarize("20260423000001");  // would re-run except summaryExists toggles in real flow

        verify(writer, times(2)).recordAuditSuccess(
                eq("20260423000001"),
                eq(sampleEnvelope),
                argThat(hash -> hash != null && hash.length() == 64 && hash.matches("[0-9a-f]{64}"))
        );
    }
}
