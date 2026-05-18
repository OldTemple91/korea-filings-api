package com.dartintel.api.api;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Redirects bare {@code /swagger-ui/<asset>} URLs to the path Springdoc
 * actually serves, which is one directory deeper:
 * {@code /swagger-ui/swagger-ui/<asset>}.
 *
 * <p>Background: with {@code springdoc.swagger-ui.path=/swagger-ui/index.html}
 * (set in {@code application.yml}) the Springdoc auto-config mounts the
 * swagger-ui webjar at {@code /swagger-ui/}, and the {@code index.html} +
 * its referenced asset files live under {@code /swagger-ui/swagger-ui/}
 * because that's where the jar layout puts them. The redirected
 * {@code index.html} then references its assets via relative paths
 * ({@code ./swagger-ui-bundle.js}, …) which resolve correctly against the
 * doubled-prefix URL — so the Swagger UI actually loads in a browser.
 *
 * <p>The problem surfaces only when an external crawler indexed the bare
 * (un-doubled) asset URLs. Observed in the 2026-05-18 production audit
 * batch: Bing's crawler hit five different bare paths in succession on
 * 2026-05-17 and received 404 on each
 * ({@code swagger-initializer.js}, {@code swagger-ui.css},
 * {@code index.css}, {@code swagger-ui-standalone-preset.js},
 * {@code swagger-ui-bundle.js}). A 301 here moves the crawler's cached
 * URL to the canonical one on the next pass and keeps the same pattern
 * working for any future indexer that probes the bare path.
 *
 * <p>The regex explicitly only matches static asset extensions
 * ({@code .js}, {@code .css}, {@code .png}). {@code .html} is excluded
 * because Springdoc already publishes a redirect at
 * {@code /swagger-ui/index.html} and we must not shadow it.
 */
@RestController
@Hidden // not part of the public OpenAPI surface
public class SwaggerUiAssetRedirectController {

    @GetMapping("/swagger-ui/{file:.+\\.(?:js|css|png)}")
    @SecurityRequirements // free, no payment
    public ResponseEntity<Void> redirectSwaggerUiAssetToActualPath(@PathVariable String file) {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .location(URI.create("/swagger-ui/swagger-ui/" + file))
                .build();
    }
}
