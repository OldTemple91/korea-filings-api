package com.dartintel.api.summarization.classifier;

import com.dartintel.api.summarization.Complexity;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ComplexityClassifier {

    private static final List<String> COMPLEX_KEYWORDS = List.of(
            "합병",        // merger
            "분할",        // split / spin-off
            "지주",        // holding company conversion
            "영업양수",    // business acquisition
            "영업양도",    // business divestiture
            "주식교환",    // stock swap
            "대규모내부거래" // large related-party transaction
    );

    private static final List<String> MEDIUM_KEYWORDS = List.of(
            "유상증자",    // rights offering
            "무상증자",    // bonus issue
            "전환사채",    // convertible bond
            "신주인수권부사채", // warrant bond
            "교환사채",    // exchangeable bond
            "감자",        // capital reduction
            "주식소각",    // stock cancellation
            "배당",        // dividend
            "자기주식취득", // treasury stock acquisition
            "자기주식처분", // treasury stock disposal
            "자사주취득",  // treasury stock acquisition (alt)
            "자사주처분"   // treasury stock disposal (alt)
    );

    public Complexity classify(String reportNm) {
        if (reportNm == null || reportNm.isBlank()) {
            return Complexity.SIMPLE;
        }
        // DART filings use inconsistent spacing (e.g. "자기주식 처분" vs "자기주식처분");
        // collapse whitespace so a single canonical keyword matches both forms.
        String normalized = reportNm.replaceAll("\\s+", "");
        if (containsAny(normalized, COMPLEX_KEYWORDS)) {
            return Complexity.COMPLEX;
        }
        if (containsAny(normalized, MEDIUM_KEYWORDS)) {
            return Complexity.MEDIUM;
        }
        return Complexity.SIMPLE;
    }

    private static boolean containsAny(String reportNm, List<String> keywords) {
        for (String keyword : keywords) {
            if (reportNm.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
