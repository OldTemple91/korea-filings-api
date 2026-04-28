package com.dartintel.api.payment;

import com.dartintel.api.payment.dto.FacilitatorSettleRequest;
import com.dartintel.api.payment.dto.FacilitatorSettleResponse;
import com.dartintel.api.payment.dto.FacilitatorVerifyRequest;
import com.dartintel.api.payment.dto.FacilitatorVerifyResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
@Slf4j
public class FacilitatorClient {

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
        return webClient.post()
                .uri("/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(s -> s.isError(), this::logAndPropagate)
                .bodyToMono(FacilitatorVerifyResponse.class)
                .timeout(readTimeout)
                .block(blockTimeout);
    }

    @CircuitBreaker(name = "facilitator")
    @Retry(name = "facilitator")
    public FacilitatorSettleResponse settle(FacilitatorSettleRequest request) {
        return webClient.post()
                .uri("/settle")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(s -> s.isError(), this::logAndPropagate)
                .bodyToMono(FacilitatorSettleResponse.class)
                .timeout(readTimeout)
                .block(blockTimeout);
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
