package com.dartintel.api.ingestion;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DartClientTest {

    private static WireMockServer wireMock;
    private DartClient client;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        DartProperties props = new DartProperties(
                new DartProperties.Api(
                        "http://localhost:" + wireMock.port() + "/api",
                        "test-key",
                        new DartProperties.Api.Timeout(2000, 3000),
                        // Per-document tuning: short read timeout so the
                        // exception-path test doesn't sit on the JDK
                        // HttpClient default; small body cap so the
                        // oversize test stays under WireMock's response
                        // budget without needing a 5 MB stub body.
                        new DartProperties.Api.Document(2000, 3000, 1024)
                ),
                new DartProperties.Polling(true, 30000, 1, 100)
        );
        client = new DartClient(WebClient.builder(), props);
    }

    @AfterEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @Test
    void parsesSuccessfulListResponseWithKoreanFields() {
        wireMock.stubFor(get(urlPathEqualTo("/api/list.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("""
                                {
                                  "status": "000",
                                  "message": "정상",
                                  "page_no": 1,
                                  "page_count": 100,
                                  "total_count": 1,
                                  "total_page": 1,
                                  "list": [{
                                    "corp_code": "00164742",
                                    "corp_name": "한국가스공사",
                                    "stock_code": "036460",
                                    "corp_cls": "Y",
                                    "report_nm": "현금ㆍ현물배당결정",
                                    "rcept_no": "20260423000123",
                                    "flr_nm": "한국가스공사",
                                    "rcept_dt": "20260423",
                                    "rm": "유"
                                  }]
                                }
                                """)));

        DartListResponse response = client.fetchList(LocalDate.of(2026, 4, 23));

        assertThat(response.status()).isEqualTo("000");
        assertThat(response.list()).hasSize(1);
        DartListResponse.DartFiling filing = response.list().get(0);
        assertThat(filing.rcptNo()).isEqualTo("20260423000123");
        assertThat(filing.corpCode()).isEqualTo("00164742");
        assertThat(filing.corpName()).isEqualTo("한국가스공사");
        assertThat(filing.reportNm()).isEqualTo("현금ㆍ현물배당결정");
        assertThat(filing.rceptDt()).isEqualTo("20260423");
        assertThat(filing.rm()).isEqualTo("유");
    }

    @Test
    void sendsApiKeyAndDateAndPageCountAsQueryParams() {
        wireMock.stubFor(get(urlPathEqualTo("/api/list.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"013\",\"message\":\"조회된 데이터가 없습니다.\"}")));

        client.fetchList(LocalDate.of(2026, 4, 23));

        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/list.json"))
                .withQueryParam("crtfc_key", equalTo("test-key"))
                .withQueryParam("bgn_de", equalTo("20260423"))
                .withQueryParam("page_count", equalTo("100")));
    }

    @Test
    void parsesNoDataResponseAsEmptyList() {
        wireMock.stubFor(get(urlPathEqualTo("/api/list.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"013\",\"message\":\"조회된 데이터가 없습니다.\"}")));

        DartListResponse response = client.fetchList(LocalDate.of(2026, 4, 23));

        assertThat(response.status()).isEqualTo("013");
        assertThat(response.list()).isNullOrEmpty();
    }

    @Test
    void ignoresUnknownFieldsFromDart() {
        wireMock.stubFor(get(urlPathEqualTo("/api/list.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "status": "000",
                                  "message": "정상",
                                  "page_no": 1,
                                  "page_count": 100,
                                  "total_count": 1,
                                  "total_page": 1,
                                  "future_field": "ignored",
                                  "list": [{
                                    "rcept_no": "20260423000001",
                                    "corp_code": "00126380",
                                    "corp_name": "삼성전자",
                                    "report_nm": "주요사항보고서",
                                    "flr_nm": "삼성전자",
                                    "rcept_dt": "20260423",
                                    "future_filing_field": "also ignored"
                                  }]
                                }
                                """)));

        DartListResponse response = client.fetchList(LocalDate.of(2026, 4, 23));

        assertThat(response.status()).isEqualTo("000");
        assertThat(response.list()).hasSize(1);
        assertThat(response.list().get(0).rcptNo()).isEqualTo("20260423000001");
    }

    // ----- fetchDocument(rcptNo) -----

    @Test
    void fetchDocumentReturnsRawZipBytes() throws Exception {
        byte[] zip = sampleZip("body.html",
                "<html><body><p>샘플 본문</p></body></html>");
        wireMock.stubFor(get(urlPathEqualTo("/api/document.xml"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/zip")
                        .withBody(zip)));

        byte[] body = client.fetchDocument("20260423000123");

        assertThat(body).isEqualTo(zip);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/document.xml"))
                .withQueryParam("crtfc_key", equalTo("test-key"))
                .withQueryParam("rcept_no", equalTo("20260423000123")));
    }

    @Test
    void fetchDocumentRejectsMalformedRcptNo() {
        assertThatThrownBy(() -> client.fetchDocument("abc123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("14 digits");
        // Reject before any HTTP call goes out.
        wireMock.verify(0,
                getRequestedFor(urlPathEqualTo("/api/document.xml")));
    }

    @Test
    void fetchDocumentSurfaces404AsIllegalState() {
        wireMock.stubFor(get(urlPathEqualTo("/api/document.xml"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client.fetchDocument("20260423000999"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("404")
                .hasMessageContaining("20260423000999");
    }

    @Test
    void fetchDocumentRejectsBodyAboveCap() throws Exception {
        // Document cap is 1024 bytes in setUp(); a 4 KB ZIP busts it.
        byte[] oversize = new byte[4096];
        for (int i = 0; i < oversize.length; i++) {
            oversize[i] = (byte) (i & 0xff);
        }
        wireMock.stubFor(get(urlPathEqualTo("/api/document.xml"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/zip")
                        .withBody(oversize)));

        assertThatThrownBy(() -> client.fetchDocument("20260423000123"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds cap");
    }

    /**
     * Build an in-memory ZIP with one entry. Used as the {@code /document.xml}
     * response in tests — DART always returns a ZIP, never the raw body.
     */
    private static byte[] sampleZip(String entryName, String body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
