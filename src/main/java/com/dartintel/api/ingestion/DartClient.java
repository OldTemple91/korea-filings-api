package com.dartintel.api.ingestion;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipInputStream;

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

    /**
     * Download and unzip the DART {@code corpCode.xml} dump.
     *
     * <p>The endpoint returns a ZIP archive containing a single
     * {@code CORPCODE.xml} file with one {@code <list>} element per
     * registered entity (~85k currently). The file is small enough to
     * buffer in memory (~6 MB compressed, ~30 MB uncompressed) so we
     * collect it into a byte array, unzip the first entry, and hand the
     * raw XML bytes back for SAX parsing in the caller. Streaming the
     * unzip would save peak memory at the cost of a more complex API
     * surface — not worth it at this scale.
     */
    @CircuitBreaker(name = "dart")
    @Retry(name = "dart")
    public byte[] fetchCorpCodeXml() {
        ByteBuffer zipped = DataBufferUtils.join(
                webClient.get()
                        .uri(uri -> uri
                                .path("/corpCode.xml")
                                .queryParam("crtfc_key", apiKey)
                                .build())
                        .retrieve()
                        .bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class))
                .map(buf -> {
                    ByteBuffer copy = ByteBuffer.allocate(buf.readableByteCount());
                    buf.toByteBuffer(copy);
                    org.springframework.core.io.buffer.DataBufferUtils.release(buf);
                    copy.flip();
                    return copy;
                })
                .timeout(readTimeout.multipliedBy(3)) // 30s default × 3 — payload ~6 MB
                .block(blockTimeout.multipliedBy(3));

        if (zipped == null) {
            throw new IllegalStateException("DART corpCode.xml returned empty body");
        }
        byte[] zipBytes = new byte[zipped.remaining()];
        zipped.get(zipBytes);

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            if (zip.getNextEntry() == null) {
                throw new IllegalStateException("DART corpCode ZIP is empty");
            }
            return zip.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to unzip DART corpCode.xml", e);
        }
    }
}
