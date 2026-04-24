package com.dartintel.api.summarization.llm;

import com.dartintel.api.summarization.DisclosureContext;
import com.dartintel.api.summarization.SummaryEnvelope;

public interface LlmClient {

    SummaryEnvelope summarize(DisclosureContext context);

    String modelId();
}
