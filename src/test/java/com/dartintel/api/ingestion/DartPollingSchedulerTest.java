package com.dartintel.api.ingestion;

import com.dartintel.api.company.CompanyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DartPollingSchedulerTest {

    @Mock
    DartClient dartClient;

    @Mock
    DisclosureRepository disclosureRepository;

    @Mock
    StringRedisTemplate redisTemplate;

    @Mock
    ValueOperations<String, String> valueOps;

    @Mock
    CompanyService companyService;

    DartPollingScheduler scheduler;

    @BeforeEach
    void setUp() {
        DartProperties props = new DartProperties(
                new DartProperties.Api(
                        "http://x", "k",
                        new DartProperties.Api.Timeout(1000, 1000),
                        new DartProperties.Api.Document(1000, 1000, 5 * 1024 * 1024)),
                new DartProperties.Polling(true, 30000, 1, 100)
        );
        scheduler = new DartPollingScheduler(
                dartClient, disclosureRepository, redisTemplate, props, companyService);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // Default to "no ticker mapping" so existing tests stay
        // unaffected. Tests that care about ticker resolution can
        // override this stub with companyService.findByCorpCode(...)
        // returning a populated Optional<Company>.
        when(companyService.findByCorpCode(anyString())).thenReturn(java.util.Optional.empty());
    }

    @Test
    void persistsNewFilingsAndAdvancesCursor() {
        when(valueOps.get(DartPollingScheduler.CURSOR_KEY)).thenReturn("20260422");
        DartListResponse.DartFiling filing = new DartListResponse.DartFiling(
                "20260423000123", "00164742", "한국가스공사",
                "036460", "Y", "현금ㆍ현물배당결정", "한국가스공사",
                "20260423", "유"
        );
        when(dartClient.fetchList(LocalDate.of(2026, 4, 22)))
                .thenReturn(new DartListResponse(
                        DartPollingScheduler.DART_STATUS_OK, "정상",
                        1, 100, 1, 1, List.of(filing)));
        when(disclosureRepository.existsByRcptNo("20260423000123")).thenReturn(false);

        scheduler.poll();

        verify(disclosureRepository).save(argThat(d ->
                d.getRcptNo().equals("20260423000123")
                        && d.getCorpName().equals("한국가스공사")
                        && d.getRceptDt().equals(LocalDate.of(2026, 4, 23))
                        && d.getRm().equals("유")
                        && d.getCorpNameEng() == null));
        // v1.1 lazy pivot: ingestion no longer pushes to the summary
        // queue; summarisation runs synchronously on the first paid call.
        verify(valueOps).set(DartPollingScheduler.CURSOR_KEY, "20260423");
    }

    @Test
    void skipsExistingRcptNoAndKeepsCursorWhenNothingNew() {
        when(valueOps.get(DartPollingScheduler.CURSOR_KEY)).thenReturn("20260422");
        DartListResponse.DartFiling filing = new DartListResponse.DartFiling(
                "20260422000005", "00164742", "한국가스공사",
                "036460", "Y", "주요사항보고서", "한국가스공사",
                "20260422", null
        );
        when(dartClient.fetchList(any())).thenReturn(new DartListResponse(
                DartPollingScheduler.DART_STATUS_OK, "정상",
                1, 100, 1, 1, List.of(filing)));
        when(disclosureRepository.existsByRcptNo("20260422000005")).thenReturn(true);

        scheduler.poll();

        verify(disclosureRepository, never()).save(any());
        verify(valueOps, never()).set(eq(DartPollingScheduler.CURSOR_KEY), anyString());
    }

    @Test
    void noOpsOnDartNoDataStatus() {
        when(valueOps.get(DartPollingScheduler.CURSOR_KEY)).thenReturn(null);
        when(dartClient.fetchList(any())).thenReturn(new DartListResponse(
                DartPollingScheduler.DART_STATUS_NO_DATA, "조회된 데이터가 없습니다.",
                null, null, null, null, null));

        scheduler.poll();

        verify(disclosureRepository, never()).save(any());
        verify(valueOps, never()).set(eq(DartPollingScheduler.CURSOR_KEY), anyString());
    }

    @Test
    void noOpsOnDartErrorStatus() {
        when(valueOps.get(DartPollingScheduler.CURSOR_KEY)).thenReturn("20260422");
        when(dartClient.fetchList(any())).thenReturn(new DartListResponse(
                "020", "요청 제한 초과",
                null, null, null, null, null));

        scheduler.poll();

        verify(disclosureRepository, never()).save(any());
        verify(valueOps, never()).set(eq(DartPollingScheduler.CURSOR_KEY), anyString());
    }

    @Test
    void swallowsClientExceptionAndDoesNotPersist() {
        when(valueOps.get(DartPollingScheduler.CURSOR_KEY)).thenReturn("20260422");
        when(dartClient.fetchList(any())).thenThrow(new RuntimeException("DART down"));

        scheduler.poll();

        verify(disclosureRepository, never()).save(any());
        verify(valueOps, never()).set(eq(DartPollingScheduler.CURSOR_KEY), anyString());
    }
}
