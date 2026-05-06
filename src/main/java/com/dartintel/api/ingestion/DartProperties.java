package com.dartintel.api.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dart")
public record DartProperties(Api api, Polling polling) {

    public record Api(String baseUrl, String key, Timeout timeout, Document document) {
        public record Timeout(int connectMs, int readMs) {
        }

        /**
         * Configuration for {@code DartClient.fetchDocument(rcptNo)} —
         * the per-filing body endpoint at {@code /api/document.xml}.
         * Body responses are ZIP files containing XBRL / HTML, with a
         * different size + latency profile than {@code /api/list.json}:
         *
         * <ul>
         *   <li>Bodies are typically 100 KB – 2 MB ZIP, occasionally
         *       larger (5+ MB for long annual reports). {@code maxBodyBytes}
         *       caps the read at 5 MB to prevent a runaway response
         *       from blowing memory.</li>
         *   <li>Read timeout needs more headroom than the list
         *       endpoint — DART can take 5-15 seconds to assemble a
         *       large filing's ZIP.</li>
         * </ul>
         *
         * Defaults are conservative for the v1.x VPS deployment shape;
         * tighten per env via {@code DART_DOCUMENT_*} variables if a
         * specific filing class shows up consistently faster / slower.
         */
        public record Document(int connectMs, int readMs, int maxBodyBytes) {
        }
    }

    public record Polling(boolean enabled, int intervalMs, int initialCursorDaysBack, int pageCount) {
    }
}
