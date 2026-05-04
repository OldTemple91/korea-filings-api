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
}
