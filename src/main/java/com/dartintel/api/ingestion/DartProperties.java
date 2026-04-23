package com.dartintel.api.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dart")
public record DartProperties(Api api, Polling polling) {

    public record Api(String baseUrl, String key, Timeout timeout) {
        public record Timeout(int connectMs, int readMs) {
        }
    }

    public record Polling(boolean enabled, int intervalMs, int initialCursorDaysBack, int pageCount) {
    }
}
