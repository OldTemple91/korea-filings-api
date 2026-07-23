package com.dartintel.api.summarization.job;

import com.dartintel.api.ingestion.Disclosure;
import com.dartintel.api.ingestion.DisclosureRepository;
import com.dartintel.api.summarization.DisclosureClassifier;
import com.dartintel.api.summarization.DisclosureSummary;
import com.dartintel.api.summarization.DisclosureSummaryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReclassifyRunnerTest {

    @Mock
    DisclosureSummaryRepository summaryRepository;

    @Mock
    DisclosureRepository disclosureRepository;

    private static Disclosure disclosure(String rcptNo, String reportNm) {
        return new Disclosure(rcptNo, "00000001", "회사", "CO,.LTD",
                reportNm, "회사", LocalDate.of(2026, 6, 15), null, "005930");
    }

    private static DisclosureSummary stubClassifiedAsOther(String rcptNo) {
        // Simulates a pre-18e stub: the rule set of the day had no
        // match, so the row was frozen as OTHER/3.
        return DisclosureSummary.fromClassification(rcptNo,
                DisclosureClassifier.classify("완전히새로운종류의공시", "005930"));
    }

    @Test
    void updatesOnlyStubsWhoseClassificationChanged() {
        // given — one stub the 18e rules now classify (governance
        // report), one that still has no matching rule.
        DisclosureSummary nowClassifiable = stubClassifiedAsOther("20260615000001");
        DisclosureSummary stillOther = stubClassifiedAsOther("20260615000002");
        when(summaryRepository.findBySummaryEnIsNullOrderByRcptNo(PageRequest.of(0, 500)))
                .thenReturn(List.of(nowClassifiable, stillOther));
        when(summaryRepository.findBySummaryEnIsNullOrderByRcptNo(PageRequest.of(1, 500)))
                .thenReturn(List.of());
        when(disclosureRepository.findAllById(anyList())).thenReturn(List.of(
                disclosure("20260615000001", "기업지배구조보고서공시"),
                disclosure("20260615000002", "완전히새로운종류의공시")));

        // when
        new ReclassifyRunner(summaryRepository, disclosureRepository)
                .run(new DefaultApplicationArguments());

        // then — only the changed row is persisted, with the new type.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DisclosureSummary>> saved = ArgumentCaptor.forClass(List.class);
        verify(summaryRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).containsExactly(nowClassifiable);
        assertThat(nowClassifiable.getEventType()).isEqualTo("CORPORATE_GOVERNANCE_REPORT");
        assertThat(nowClassifiable.getImportanceScore()).isEqualTo(1);
        assertThat(stillOther.getEventType()).isEqualTo("OTHER");
    }

    @Test
    void noChangesMeansNoWrites() {
        DisclosureSummary upToDate = DisclosureSummary.fromClassification("20260615000003",
                DisclosureClassifier.classify("기업지배구조보고서공시", "005930"));
        when(summaryRepository.findBySummaryEnIsNullOrderByRcptNo(PageRequest.of(0, 500)))
                .thenReturn(List.of(upToDate));
        when(summaryRepository.findBySummaryEnIsNullOrderByRcptNo(PageRequest.of(1, 500)))
                .thenReturn(List.of());
        when(disclosureRepository.findAllById(anyList()))
                .thenReturn(List.of(disclosure("20260615000003", "기업지배구조보고서공시")));

        new ReclassifyRunner(summaryRepository, disclosureRepository)
                .run(new DefaultApplicationArguments());

        verify(summaryRepository, never()).saveAll(any());
    }

    @Test
    void llmRowsAreNeverTouchedEvenIfClassificationDiffers() {
        // Entity-level guard: a row carrying the paid LLM summary is
        // immutable product regardless of what the current rules say.
        DisclosureSummary llmRow = stubClassifiedAsOther("20260615000004");
        llmRow.overlayLlmSummary("A real English summary.", 5, "IR_EVENT",
                List.of(), List.of("005930"), List.of("traders"),
                "gemini-2.5-flash-lite", 100, 50,
                new java.math.BigDecimal("0.00030000"), (short) 1);

        boolean changed = llmRow.applyReclassification(
                DisclosureClassifier.classify("기업지배구조보고서공시", "005930"));

        assertThat(changed).isFalse();
        assertThat(llmRow.getEventType()).isEqualTo("IR_EVENT");
    }
}
