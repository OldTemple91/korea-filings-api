package com.dartintel.api.summarization.job;

import com.dartintel.api.summarization.DisclosureSummaryRepository;
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
    DisclosureSummaryRepository summaryRepository;

    @Mock
    SummaryJobQueue queue;

    BackfillRunner runner;

    @BeforeEach
    void setUp() {
        runner = new BackfillRunner(summaryRepository, queue);
        ReflectionTestUtils.setField(runner, "maxItems", 500);
        ReflectionTestUtils.setField(runner, "minImportance", 7);
    }

    @Test
    void enqueuesEveryStubReturnedByRepo() {
        when(summaryRepository.findStubRcptNos(7, PageRequest.of(0, 500)))
                .thenReturn(List.of("20260722000001", "20260722000002", "20260722000003"));

        runner.run(new DefaultApplicationArguments());

        verify(queue).push("20260722000001");
        verify(queue).push("20260722000002");
        verify(queue).push("20260722000003");
    }

    @Test
    void emptyResultSetIsANoOp() {
        when(summaryRepository.findStubRcptNos(7, PageRequest.of(0, 500)))
                .thenReturn(List.of());

        runner.run(new DefaultApplicationArguments());

        verifyNoInteractions(queue);
    }

    @Test
    void respectsConfiguredMaxItemsAndImportanceFloor() {
        ReflectionTestUtils.setField(runner, "maxItems", 42);
        ReflectionTestUtils.setField(runner, "minImportance", 5);
        when(summaryRepository.findStubRcptNos(5, PageRequest.of(0, 42)))
                .thenReturn(List.of("X"));

        runner.run(new DefaultApplicationArguments());

        verify(summaryRepository).findStubRcptNos(eq(5), eq(PageRequest.of(0, 42)));
        verify(queue).push("X");
        verify(queue, never()).push("Y");
    }
}
