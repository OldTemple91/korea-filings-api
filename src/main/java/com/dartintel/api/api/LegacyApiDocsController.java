package com.dartintel.api.api;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * springdoc has been moved to {@code /openapi.json} — the de-facto
 * standard location for OpenAPI specs that every discovery tool and
 * x402 indexer probes first. This controller keeps the legacy
 * {@code /v3/api-docs} path alive as a 301 redirect so any consumer
 * that pinned the old URL (early READMEs, shared SDK pointers, third-
 * party listings) keeps resolving.
 */
@RestController
@Hidden  // do not surface this redirect endpoint inside the OpenAPI doc itself
public class LegacyApiDocsController {

    @GetMapping("/v3/api-docs")
    @SecurityRequirements
    public ResponseEntity<Void> redirectToOpenApiJson() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .location(URI.create("/openapi.json"))
                .build();
    }
}
