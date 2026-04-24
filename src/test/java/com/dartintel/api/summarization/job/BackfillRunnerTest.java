package com.dartintel.api.summarization.job;

import com.dartintel.api.ingestion.DisclosureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackfillRunnerTest {

    @Mock
    DisclosureRepository disclosureRepository;

    @Mock
    SummaryJobQueue queue;

    BackfillRunner runner;

    @BeforeEach
    void setUp() {
        runner = new BackfillRunner(disclosureRepository, queue);
        ReflectionTestUtils.setField(runner, "maxItems", 500);
    }

    @Test
    void enqueuesEveryMissingRcptNoReturnedByRepo() {
        when(disclosureRepository.findRcptNosMissingSummary(PageRequest.of(0, 500)))
                .thenReturn(List.of("20260424000001", "20260424000002", "20260424000003"));

        runner.run(new DefaultApplicationArguments());

        verify(queue).push("20260424000001");
        verify(queue).push("20260424000002");
        verify(queue).push("20260424000003");
    }

    @Test
    void emptyResultSetIsANoOp() {
        when(disclosureRepository.findRcptNosMissingSummary(PageRequest.of(0, 500)))
                .thenReturn(List.of());

        runner.run(new DefaultApplicationArguments());

        verifyNoInteractions(queue);
    }

    @Test
    void respectsConfiguredMaxItems() {
        ReflectionTestUtils.setField(runner, "maxItems", 42);
        when(disclosureRepository.findRcptNosMissingSummary(PageRequest.of(0, 42)))
                .thenReturn(List.of("X"));

        runner.run(new DefaultApplicationArguments());

        verify(disclosureRepository).findRcptNosMissingSummary(eq(PageRequest.of(0, 42)));
        verify(queue).push("X");
        verify(queue, never()).push("Y");
    }
}
