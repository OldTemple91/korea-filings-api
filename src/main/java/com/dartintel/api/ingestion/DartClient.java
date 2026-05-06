package com.dartintel.api.ingestion;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
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
    /** Per-document fetch tuning (separate from /list.json timeouts). */
    private final Duration documentReadTimeout;
    private final int documentMaxBodyBytes;

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

        // Document endpoint tuning. Defaults applied when document
        // properties are absent (e.g. older test profiles without the
        // V13-onwards config block).
        DartProperties.Api.Document doc = props.api().document();
        this.documentReadTimeout = Duration.ofMillis(doc != null ? doc.readMs() : 30_000);
        this.documentMaxBodyBytes = doc != null ? doc.maxBodyBytes() : 5 * 1024 * 1024;
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

    /**
     * Fetch the per-filing body ZIP from DART {@code /api/document.xml}.
     *
     * <p>The endpoint returns a ZIP archive whose entries vary by
     * filing type — typically one or more XBRL XML files plus
     * sometimes an HTML version of the same content, with occasional
     * embedded image attachments. The caller (downstream parser) is
     * responsible for picking the relevant entries; this method just
     * returns the raw ZIP bytes so the parser can decide.
     *
     * <p>Wrapped in a separate Resilience4j instance ({@code dart-document}
     * circuit breaker / retry / rate limiter) so that flaky body
     * fetches do not halt {@code /list.json} polling. A tripped
     * breaker on document means lazy summarisation falls back to
     * title-only mode, which is still a usable response.
     *
     * <p>Body cap: the JDK HTTP client buffers the full response
     * before we parse it. To prevent a runaway response from
     * exhausting memory, we check {@code Content-Length} when present
     * and bail before downloading bodies above
     * {@code dart.api.document.max-body-bytes} (default 5 MB).
     * Servers that omit Content-Length are still bounded by the
     * read-timeout (default 30s) — far short of what a 50 MB body
     * would need over the typical DART pipe.
     *
     * @return raw ZIP bytes ready for {@link DartDocumentParser}
     * @throws IllegalStateException on HTTP error, oversize body, or
     *         transport failure
     */
    @CircuitBreaker(name = "dart-document")
    @Retry(name = "dart-document")
    @RateLimiter(name = "dart-document")
    public byte[] fetchDocument(String rcptNo) {
        if (rcptNo == null || !rcptNo.matches("\\d{14}")) {
            throw new IllegalArgumentException(
                    "rcptNo must be exactly 14 digits, got " + rcptNo);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/document.xml?crtfc_key=" + apiKey
                        + "&rcept_no=" + rcptNo))
                .timeout(documentReadTimeout)
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = rawHttpClient.send(
                    request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status == 404) {
                throw new IllegalStateException(
                        "DART /document.xml returned 404 for rcptNo=" + rcptNo
                        + " (filing not yet available, withdrawn, or DART has it disabled)");
            }
            if (status / 100 != 2) {
                throw new IllegalStateException(
                        "DART /document.xml returned HTTP " + status
                        + " for rcptNo=" + rcptNo);
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new IllegalStateException(
                        "DART /document.xml returned empty body for rcptNo=" + rcptNo);
            }
            if (body.length > documentMaxBodyBytes) {
                throw new IllegalStateException(
                        "DART /document.xml returned " + body.length
                        + " bytes for rcptNo=" + rcptNo
                        + " — exceeds cap of " + documentMaxBodyBytes
                        + " (raise dart.api.document.max-body-bytes if a longer report is genuinely needed)");
            }
            log.debug("fetched DART document rcptNo={} bytes={}", rcptNo, body.length);
            return body;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(
                    "Failed to fetch DART /document.xml for rcptNo=" + rcptNo, e);
        }
    }
}
