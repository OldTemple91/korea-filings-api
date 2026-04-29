package com.dartintel.api.ingestion;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipInputStream;

@Component
@Slf4j
public class DartClient {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final WebClient webClient;
    /**
     * Direct JDK {@link HttpClient} kept alongside the WebClient because
     * the corpCode.xml dump is a streaming ZIP body that the
     * Reactor-style {@code DataBufferUtils.join} flow occasionally
     * truncates or mangles in our environment. Using the JDK client
     * with {@code BodyHandlers.ofByteArray()} forces a single, fully
     * buffered byte[] which the ZipInputStream then reliably parses.
     */
    private final HttpClient rawHttpClient;
    private final String baseUrl;
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
        this.rawHttpClient = jdkHttpClient;
        this.baseUrl = props.api().baseUrl();
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
        // The corpCode.xml dump is a ~3-6 MB ZIP that DART serves at ~10
        // KB/s, so the full transfer takes 5-10 minutes end-to-end. The
        // CompanySyncScheduler runs this off the main thread so the slow
        // body does not block app startup; we still need a generous
        // timeout here to let the body finish on a healthy run. 12
        // minutes covers observed real-world p99 plus margin; Resilience4j
        // retry gives us one more attempt on a truly transient failure.
        //
        // Using the raw JDK HttpClient (not WebClient) because the
        // Reactor-style DataBuffer join we tried first occasionally
        // produced a truncated byte stream that ZipInputStream then
        // rejected with a header-CRC error — switching to
        // BodyHandlers.ofByteArray() makes the JDK client buffer the
        // whole body before we parse it, eliminating that whole class
        // of failure.
        Duration corpCodeTimeout = Duration.ofMinutes(12);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/corpCode.xml?crtfc_key=" + apiKey))
                .timeout(corpCodeTimeout)
                .GET()
                .build();

        byte[] zipBytes;
        try {
            HttpResponse<byte[]> response = rawHttpClient.send(
                    request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "DART corpCode.xml returned HTTP " + response.statusCode());
            }
            zipBytes = response.body();
            if (zipBytes == null || zipBytes.length == 0) {
                throw new IllegalStateException("DART corpCode.xml returned empty body");
            }
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to download DART corpCode.xml", e);
        }

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            if (zip.getNextEntry() == null) {
                throw new IllegalStateException(
                        "DART corpCode ZIP is empty (downloaded " + zipBytes.length + " bytes)");
            }
            return zip.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to unzip DART corpCode.xml (received " + zipBytes.length + " bytes)", e);
        }
    }
}
