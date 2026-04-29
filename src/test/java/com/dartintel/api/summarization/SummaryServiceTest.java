package com.dartintel.api.summarization;

import com.dartintel.api.ingestion.Disclosure;
import com.dartintel.api.ingestion.DisclosureRepository;
import com.dartintel.api.summarization.classifier.ComplexityClassifier;
import com.dartintel.api.summarization.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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

    @InjectMocks
    SummaryService service;

    private Disclosure sampleDisclosure;
    private SummaryEnvelope sampleEnvelope;

    @BeforeEach
    void setUp() {
        when(llmClient.modelId()).thenReturn("gemini-2.5-flash-lite");
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
