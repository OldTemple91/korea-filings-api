package com.dartintel.api.summarization.job;

import com.dartintel.api.ingestion.DisclosureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummaryRetrySchedulerTest {

    @Mock
    DisclosureRepository disclosureRepository;

    @Mock
    SummaryJobQueue queue;

    SummaryRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SummaryRetryScheduler(disclosureRepository, queue);
        ReflectionTestUtils.setField(scheduler, "maxPerCycle", 100);
    }

    @Test
    void reEnqueuesEveryOrphanReturnedByRepository() {
        when(disclosureRepository.findRcptNosMissingSummary(PageRequest.of(0, 100)))
                .thenReturn(List.of("20260424000001", "20260424000002"));

        scheduler.retryOrphanedSummaries();

        verify(queue).push("20260424000001");
        verify(queue).push("20260424000002");
    }

    @Test
    void emptyResultIsANoOp() {
        when(disclosureRepository.findRcptNosMissingSummary(PageRequest.of(0, 100)))
                .thenReturn(List.of());

        scheduler.retryOrphanedSummaries();

        verifyNoInteractions(queue);
    }

    @Test
    void respectsMaxPerCycleOverride() {
        ReflectionTestUtils.setField(scheduler, "maxPerCycle", 25);
        when(disclosureRepository.findRcptNosMissingSummary(PageRequest.of(0, 25)))
                .thenReturn(List.of("X"));

        scheduler.retryOrphanedSummaries();

        verify(disclosureRepository).findRcptNosMissingSummary(eq(PageRequest.of(0, 25)));
        verify(queue).push("X");
    }
}
