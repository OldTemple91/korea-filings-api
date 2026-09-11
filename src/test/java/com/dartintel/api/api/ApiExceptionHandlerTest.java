package com.dartintel.api.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-21: the free feed was being polled for hours by a client that
 * only ever received 400s. Two gaps: a non-numeric query value fell
 * through to Spring's bare "Bad Request" (no message, no hint), and
 * the {@code /recent} validation hint pointed at {@code /v1/pricing}
 * instead of stating the accepted ranges.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void typeMismatchOnRecentLimitReturns400WithRangesInHint() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/disclosures/recent");
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException("abc", int.class, "limit", null, null);

        ResponseEntity<Map<String, Object>> response = handler.handleTypeMismatch(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("error", "validation_failed");
        assertThat((String) response.getBody().get("message")).contains("limit").contains("abc");
        assertThat((String) response.getBody().get("agent_action_hint"))
                .contains("limit").contains("1").contains("100")
                .contains("since_hours").contains("168");
    }

    @Test
    void typeMismatchOnByTickerLimitKeepsEndpointSpecificHint() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/disclosures/by-ticker");
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException("x", int.class, "limit", null, null);

        ResponseEntity<Map<String, Object>> response = handler.handleTypeMismatch(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat((String) response.getBody().get("agent_action_hint")).contains("/v1/companies?q=");
    }
}
