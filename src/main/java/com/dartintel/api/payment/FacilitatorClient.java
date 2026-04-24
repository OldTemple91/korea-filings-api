package com.dartintel.api.payment;

import com.dartintel.api.payment.dto.FacilitatorSettleRequest;
import com.dartintel.api.payment.dto.FacilitatorSettleResponse;
import com.dartintel.api.payment.dto.FacilitatorVerifyRequest;
import com.dartintel.api.payment.dto.FacilitatorVerifyResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
@Slf4j
public class FacilitatorClient {

    private final WebClient webClient;
    private final Duration readTimeout;
    private final Duration blockTimeout;

    public FacilitatorClient(WebClient.Builder builder, X402Properties props) {
        HttpClient jdkHttpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(props.timeout().connectMs()))
                .build();
        this.webClient = builder.clone()
                .baseUrl(props.facilitatorUrl())
                .clientConnector(new JdkClientHttpConnector(jdkHttpClient))
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
                .bodyToMono(FacilitatorSettleResponse.class)
                .timeout(readTimeout)
                .block(blockTimeout);
    }
}
