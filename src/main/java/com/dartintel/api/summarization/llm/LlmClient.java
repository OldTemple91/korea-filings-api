package com.dartintel.api.summarization.llm;

import com.dartintel.api.summarization.DisclosureContext;
import com.dartintel.api.summarization.SummaryResult;

public interface LlmClient {

    SummaryResult summarize(DisclosureContext context);
}
