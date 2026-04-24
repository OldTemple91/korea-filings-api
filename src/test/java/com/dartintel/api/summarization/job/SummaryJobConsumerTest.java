package com.dartintel.api.summarization.job;

import com.dartintel.api.summarization.SummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummaryJobConsumerTest {

    @Mock
    SummaryJobQueue queue;

    @Mock
    SummaryService summaryService;

    SummaryJobConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SummaryJobConsumer(queue, summaryService);
    }

    @Test
    void processOneDelegatesToSummaryServiceWithPoppedRcptNo() {
        when(queue.pop(any(Duration.class))).thenReturn("20260424000001");

        consumer.processOne();

        verify(summaryService).summarize("20260424000001");
    }

    @Test
    void processOneNoOpsWhenQueueReturnsNull() {
        when(queue.pop(any(Duration.class))).thenReturn(null);

        consumer.processOne();

        verifyNoInteractions(summaryService);
    }

    @Test
    void processOneSwallowsSummaryServiceExceptions() {
        when(queue.pop(any(Duration.class))).thenReturn("20260424000002");
        org.mockito.Mockito.doThrow(new RuntimeException("Gemini down"))
                .when(summaryService).summarize("20260424000002");

        consumer.processOne();  // no exception escapes

        verify(summaryService).summarize("20260424000002");
    }

    @Test
    void processOneWithEmptyQueueNeverCallsSummarize() {
        when(queue.pop(any(Duration.class))).thenReturn(null);

        consumer.processOne();
        consumer.processOne();
        consumer.processOne();

        verify(summaryService, never()).summarize(any());
    }
}
