package com.dartintel.api.ingestion;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

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
                        new DartProperties.Api.Timeout(2000, 3000)
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
}
