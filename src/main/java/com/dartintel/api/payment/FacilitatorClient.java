package com.dartintel.api.payment;

import com.dartintel.api.payment.dto.ExtensionResponse;
import com.dartintel.api.payment.dto.FacilitatorSettleRequest;
import com.dartintel.api.payment.dto.FacilitatorSettleResponse;
import com.dartintel.api.payment.dto.FacilitatorVerifyRequest;
import com.dartintel.api.payment.dto.FacilitatorVerifyResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
public class FacilitatorClient {

    /**
     * Facilitator-side outcome of the extensions carried in the
     * payload (x402 extensions spec). For {@code bazaar} this is how
     * CDP reports whether the resource was accepted for cataloging —
     * the only signal a seller gets that discovery indexing will
     * actually happen.
     */
    static final String EXTENSION_RESPONSES_HEADER = "EXTENSION-RESPONSES";
    private static final ObjectMapper HEADER_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, ExtensionResponse>> EXTENSION_MAP =
            new TypeReference<>() {
            };

    private final WebClient webClient;
    private final Duration readTimeout;
    private final Duration blockTimeout;

    public FacilitatorClient(WebClient.Builder builder, X402Properties props, CdpJwtSigner cdpSigner) {
        HttpClient jdkHttpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(props.timeout().connectMs()))
                .build();
        this.webClient = builder.clone()
                .baseUrl(props.facilitatorUrl())
                .clientConnector(new JdkClientHttpConnector(jdkHttpClient))
                // Attach a fresh CDP JWT to every request when the merchant
                // is configured for mainnet. The filter is a no-op on the
                // public testnet facilitator (signer.sign returns null).
                .filter(cdpAuthFilter(cdpSigner))
                .build();
        this.readTimeout = Duration.ofMillis(props.timeout().readMs());
        this.blockTimeout = Duration.ofMillis(
                props.timeout().connectMs() + props.timeout().readMs() + 5_000L);
    }

    @CircuitBreaker(name = "facilitator")
    @Retry(name = "facilitator")
    public FacilitatorVerifyResponse verify(FacilitatorVerifyRequest request) {
        ResponseEntity<FacilitatorVerifyResponse> entity = webClient.post()
                .uri("/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(s -> s.isError(), this::logAndPropagate)
                .toEntity(FacilitatorVerifyResponse.class)
                .timeout(readTimeout)
                .block(blockTimeout);
        if (entity == null || entity.getBody() == null) {
            return null;
        }
        Map<String, ExtensionResponse> ext = parseExtensionResponses(
                entity.getHeaders().getFirst(EXTENSION_RESPONSES_HEADER));
        logExtensionResponses("verify", ext);
        return entity.getBody().withExtensionResponses(ext);
    }

    @CircuitBreaker(name = "facilitator")
    @Retry(name = "facilitator")
    public FacilitatorSettleResponse settle(FacilitatorSettleRequest request) {
        ResponseEntity<FacilitatorSettleResponse> entity = webClient.post()
                .uri("/settle")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(s -> s.isError(), this::logAndPropagate)
                .toEntity(FacilitatorSettleResponse.class)
                .timeout(readTimeout)
                .block(blockTimeout);
        if (entity == null || entity.getBody() == null) {
            return null;
        }
        Map<String, ExtensionResponse> ext = parseExtensionResponses(
                entity.getHeaders().getFirst(EXTENSION_RESPONSES_HEADER));
        logExtensionResponses("settle", ext);
        return entity.getBody().withExtensionResponses(ext);
    }

    /**
     * Decode the base64-JSON {@code EXTENSION-RESPONSES} header into a
     * per-extension status map. Never throws: a missing, malformed or
     * non-object header yields an empty map, because the payment
     * outcome itself must not depend on an advisory header.
     */
    static Map<String, ExtensionResponse> parseExtensionResponses(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return Map.of();
        }
        try {
            byte[] json = Base64.getDecoder().decode(headerValue.trim());
            Map<String, ExtensionResponse> parsed = HEADER_MAPPER.readValue(json, EXTENSION_MAP);
            Map<String, ExtensionResponse> out = new LinkedHashMap<>();
            parsed.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(k, v);
                }
            });
            return Map.copyOf(out);
        } catch (Exception e) {
            log.debug("Ignoring unparseable {} header: {}", EXTENSION_RESPONSES_HEADER, e.getMessage());
            return Map.of();
        }
    }

    private static void logExtensionResponses(String op, Map<String, ExtensionResponse> ext) {
        ExtensionResponse bazaar = ext.get("bazaar");
        if (bazaar == null) {
            return;
        }
        if ("rejected".equalsIgnoreCase(bazaar.status())) {
            log.warn("facilitator {}: bazaar cataloging rejected: {}", op,
                    X402PaywallInterceptor.sanitiseLogValue(String.valueOf(bazaar.rejectedReason())));
        } else {
            log.info("facilitator {}: bazaar cataloging status={}", op,
                    X402PaywallInterceptor.sanitiseLogValue(String.valueOf(bazaar.status())));
        }
    }

    /**
     * Surface the facilitator's error body in our logs before letting
     * the exception bubble up. Without this, a 4xx from CDP only shows
     * the status code — the JSON body that explains why is invisible,
     * which makes debugging mainnet integration painful.
     */
    private Mono<Throwable> logAndPropagate(org.springframework.web.reactive.function.client.ClientResponse resp) {
        return resp.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> {
                    log.error("Facilitator {} response body: {}", resp.statusCode(), body);
                    return WebClientResponseException.create(
                            resp.statusCode().value(),
                            resp.statusCode().toString(),
                            resp.headers().asHttpHeaders(),
                            body.getBytes(),
                            null
                    );
                });
    }

    private static ExchangeFilterFunction cdpAuthFilter(CdpJwtSigner signer) {
        return (request, next) -> {
            String token = signer.sign(
                    request.method().name(),
                    request.url().toString()
            );
            if (token == null) {
                return next.exchange(request);
            }
            ClientRequest signed = ClientRequest.from(request)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .build();
            return next.exchange(signed);
        };
    }
}
