package com.dartintel.api.ingestion;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
@Slf4j
public class DartClient {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final WebClient webClient;
    private final String apiKey;
    private final int pageCount;
    private final Duration readTimeout;
    private final Duration blockTimeout;

    public DartClient(WebClient.Builder builder, DartProperties props) {
        DartProperties.Api.Timeout timeout = props.api().timeout();

        // Use the JDK HttpClient connector instead of Reactor Netty: opendart.fss.or.kr
        // rejects Netty's default ClientHello with TLS handshake_failure, while the
        // JDK HttpClient negotiates the same TLS 1.2 / AES128-GCM-SHA256 cleanly.
        HttpClient jdkHttpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeout.connectMs()))
                .build();

        this.webClient = builder.clone()
                .baseUrl(props.api().baseUrl())
                .clientConnector(new JdkClientHttpConnector(jdkHttpClient))
                .build();
        this.apiKey = props.api().key();
        this.pageCount = props.polling().pageCount();
        this.readTimeout = Duration.ofMillis(timeout.readMs());
        this.blockTimeout = Duration.ofMillis(timeout.connectMs() + timeout.readMs() + 5000L);
    }

    @CircuitBreaker(name = "dart")
    @Retry(name = "dart")
    public DartListResponse fetchList(LocalDate beginDate) {
        return webClient.get()
                .uri(uri -> uri
                        .path("/list.json")
                        .queryParam("crtfc_key", apiKey)
                        .queryParam("bgn_de", beginDate.format(YYYYMMDD))
                        .queryParam("page_count", pageCount)
                        .build())
                .retrieve()
                .bodyToMono(DartListResponse.class)
                .timeout(readTimeout)
                .block(blockTimeout);
    }
}
