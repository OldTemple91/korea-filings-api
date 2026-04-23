package com.dartintel.api.summarization.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gemini")
public record GeminiProperties(String baseUrl, String key, Timeout timeout) {

    public record Timeout(int connectMs, int readMs) {
    }
}
