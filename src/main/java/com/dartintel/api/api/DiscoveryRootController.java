package com.dartintel.api.api;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Catches the two paths that crawlers and casual visitors hit by
 * default but that have no business value if served as 404s.
 *
 * <p>{@code GET /} 302-redirects to the marketing landing page so a
 * human who pastes the API host into a browser sees the product
 * description, not a JSON 404. Agents that follow the redirect end up
 * at the landing page, which itself links back to {@code /llms.txt}
 * and {@code /.well-known/x402} — so the discovery loop stays closed.
 *
 * <p>{@code GET /favicon.ico} returns 204 No Content so browsers stop
 * spamming the access log with 404s. Branded favicon delivery is a
 * separate, lower-priority follow-up; for now an empty response is the
 * cheapest signal that "yes, this domain knows what favicon.ico is".
 */
@RestController
@Tag(name = "Discovery", description = "Catch-all handlers for the root path and favicon to avoid 404 noise.")
public class DiscoveryRootController {

    private static final String LANDING_URL = "https://koreafilings.com";

    @GetMapping("/")
    @SecurityRequirements
    @Hidden
    public ResponseEntity<Void> root() {
        return ResponseEntity
                .status(302)
                .header(HttpHeaders.LOCATION, LANDING_URL)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .build();
    }

    @GetMapping("/favicon.ico")
    @SecurityRequirements
    @Hidden
    public ResponseEntity<Void> favicon() {
        // Cache the no-content response for a day so a single
        // browser tab doesn't beat on us.
        return ResponseEntity
                .noContent()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .build();
    }
}
