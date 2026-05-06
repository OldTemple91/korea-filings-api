package com.dartintel.api.observability;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level coverage for the audit log line formatter. The filter
 * itself is wired into Spring via {@code @Component} +
 * {@code @ConditionalOnProperty}; any breakage there shows up in a
 * full-context IT test, so this class focuses on the deterministic
 * formatting behaviour we depend on for grep / awk analysis.
 */
class RequestAuditFilterTest {

    @Test
    void postWithKnownQueryKeyAndPaymentSignaturePresent() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/disclosures/summary");
        req.setQueryString("rcptNo=20260424900874");
        req.setParameter("rcptNo", "20260424900874");
        req.setContentType("application/json");
        req.setContent("{\"rcptNo\":\"...\"}".getBytes());
        req.addHeader("User-Agent", "axios/1.14.0");
        req.addHeader("CF-Connecting-IP", "104.131.41.96");
        req.addHeader("PAYMENT-SIGNATURE", "eyJ...");

        String line = RequestAuditFilter.formatLine(req, 405);

        assertThat(line)
                .contains("method=POST")
                .contains("path=/v1/disclosures/summary")
                .contains("status=405")
                .contains("ip=104.131.41.96")
                .contains("ua=axios/1.14.0")
                .contains("query=[rcptNo]")
                .contains("ct=application/json")
                .contains("xpay=false")
                .contains("pay_sig=true");
        // body length comes from the actual byte count, not the
        // hand-set Content-Length header
        assertThat(line).containsPattern("body=\\d+");
    }

    @Test
    void getWithoutQueryParamsRendersEmptyKeyList() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/pricing");

        String line = RequestAuditFilter.formatLine(req, 200);

        assertThat(line)
                .contains("query=[]")
                .contains("body=-")          // no Content-Length on empty GET
                .contains("ct=-")            // no Content-Type
                .contains("ua=-")            // no UA
                .contains("xpay=false")
                .contains("pay_sig=false");
    }

    @Test
    void multipleQueryKeysAreSortedAlphabetically() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/disclosures/by-ticker");
        req.setParameter("ticker", "005930");
        req.setParameter("limit", "5");

        String line = RequestAuditFilter.formatLine(req, 402);

        // TreeSet ordering — "limit" comes before "ticker" lexicographically
        assertThat(line).contains("query=[limit, ticker]");
    }

    @Test
    void cfConnectingIpTakesPrecedenceOverRemoteAddr() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/companies");
        req.setRemoteAddr("172.18.0.5"); // docker bridge
        req.addHeader("CF-Connecting-IP", "203.0.113.42");

        String line = RequestAuditFilter.formatLine(req, 200);

        assertThat(line).contains("ip=203.0.113.42").doesNotContain("ip=172.18.0.5");
    }

    @Test
    void fallsBackToRemoteAddrWhenCfHeaderMissing() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/companies");
        req.setRemoteAddr("203.0.113.42");

        String line = RequestAuditFilter.formatLine(req, 500);

        assertThat(line).contains("ip=203.0.113.42");
    }

    @Test
    void uaWithSpacesIsQuotedSoLineStaysParseable() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/companies");
        req.addHeader("User-Agent", "Mozilla/5.0 (Macintosh; ARM Mac OS X 14_4)");

        String line = RequestAuditFilter.formatLine(req, 200);

        assertThat(line).contains("ua=\"Mozilla/5.0 (Macintosh; ARM Mac OS X 14_4)\"");
    }

    @Test
    void crlfInUserAgentIsStrippedToDefangLogInjection() {
        // A crafted UA that tries to inject a fake follow-up audit
        // line should be neutered before it reaches the log file.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/companies");
        req.addHeader("User-Agent", "evil\r\nREQ_AUDIT method=GET fake=1");

        String line = RequestAuditFilter.formatLine(req, 200);

        assertThat(line).doesNotContain("\r").doesNotContain("\n");
        // CR/LF are replaced with spaces, so the fake tag survives
        // verbatim — but on a single line, attached to the legitimate
        // `ua=` field. Grep / awk parsing is now safe.
        assertThat(line).contains("ua=\"evil  REQ_AUDIT method=GET fake=1\"");
    }

    @Test
    void xPaymentHeaderPresenceFlagsTrueWithoutLeakingValue() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/disclosures/summary");
        // X-PAYMENT carries a base64-encoded signed authorisation —
        // the value MUST stay out of logs even when its presence is
        // useful signal.
        req.addHeader("X-PAYMENT", "eyJ4NDAyVmVyc2lvbiI6MiwiYWNjZXB0c0lkIjoiLi4uIn0=");

        String line = RequestAuditFilter.formatLine(req, 200);

        assertThat(line).contains("xpay=true");
        assertThat(line).doesNotContain("eyJ4NDAyVmVyc2lvbiI");
    }

    @Test
    void overlongUserAgentIsTruncated() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/companies");
        req.addHeader("User-Agent", "x".repeat(500));

        String line = RequestAuditFilter.formatLine(req, 200);

        // Truncated to 200 chars + ellipsis. The exact length is an
        // implementation detail of sanitise(); just verify the cap
        // landed and the suffix is there.
        assertThat(line).contains("...");
        assertThat(line.length()).isLessThan(500);
    }

    // ---- toRow() ----  the DB-bound mapping that mirrors formatLine()

    @Test
    void toRowCapturesAllStructuralFieldsWithoutHeaderValues() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/disclosures/summary");
        req.setParameter("rcptNo", "20260424900874");
        req.setContentType("application/json");
        req.setContent("{\"rcpt_no\":\"...\"}".getBytes());
        req.addHeader("User-Agent", "axios/1.14.0");
        req.addHeader("CF-Connecting-IP", "104.131.41.96");
        req.addHeader("PAYMENT-SIGNATURE", "eyJ4NDAyVmVyc2lvbiI6Mn0=");

        RequestAudit row = RequestAuditFilter.toRow(req, 405);

        assertThat(row.getMethod()).isEqualTo("POST");
        assertThat(row.getPath()).isEqualTo("/v1/disclosures/summary");
        assertThat(row.getStatus()).isEqualTo(405);
        assertThat(row.getIp()).isEqualTo("104.131.41.96");
        assertThat(row.getUserAgent()).isEqualTo("axios/1.14.0");
        assertThat(row.getQueryKeys()).isEqualTo("rcptNo");
        assertThat(row.getContentType()).isEqualTo("application/json");
        assertThat(row.getBodyBytes()).isPositive();
        assertThat(row.isHasPaymentSig()).isTrue();
        assertThat(row.isHasXPayment()).isFalse();
        // Header VALUES never end up on any row field — only the
        // boolean. Sweep every getter that returns a String to catch
        // any accidental future regression that copies the header.
        assertThat(row.getMethod()).doesNotContain("eyJ4NDAyVmVyc2lvbiI6Mn0=");
        assertThat(row.getPath()).doesNotContain("eyJ4NDAyVmVyc2lvbiI6Mn0=");
        assertThat(row.getIp()).doesNotContain("eyJ4NDAyVmVyc2lvbiI6Mn0=");
        assertThat(row.getUserAgent()).doesNotContain("eyJ4NDAyVmVyc2lvbiI6Mn0=");
    }

    @Test
    void toRowWithMultipleQueryKeysIsCommaSeparatedSorted() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/disclosures/by-ticker");
        req.setParameter("ticker", "005930");
        req.setParameter("limit", "5");

        RequestAudit row = RequestAuditFilter.toRow(req, 402);

        // TreeSet ordering: limit before ticker.
        assertThat(row.getQueryKeys()).isEqualTo("limit,ticker");
    }

    @Test
    void toRowWithoutQueryKeysIsNullNotEmptyString() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/pricing");

        RequestAudit row = RequestAuditFilter.toRow(req, 200);

        // null distinguishes "no query string" from "empty key list"
        // when querying — `WHERE query_keys IS NULL` reads cleaner
        // than `WHERE query_keys = ''`.
        assertThat(row.getQueryKeys()).isNull();
        assertThat(row.getBodyBytes()).isNull();
        assertThat(row.getContentType()).isNull();
    }

    @Test
    void toRowTruncatesOverlongFieldsToColumnLimits() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/" + "a".repeat(400));
        req.addHeader("User-Agent", "x".repeat(500));

        RequestAudit row = RequestAuditFilter.toRow(req, 404);

        // path column is VARCHAR(256), user_agent VARCHAR(256)
        assertThat(row.getPath().length()).isLessThanOrEqualTo(256);
        assertThat(row.getUserAgent().length()).isLessThanOrEqualTo(256);
        assertThat(row.getPath()).endsWith("...");
        assertThat(row.getUserAgent()).endsWith("...");
    }
}
