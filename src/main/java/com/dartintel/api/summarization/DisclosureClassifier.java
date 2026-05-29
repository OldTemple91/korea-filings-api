package com.dartintel.api.summarization;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Rule-based classifier that maps a Korean DART {@code report_nm}
 * (filing title) to the same shape an LLM summary would produce, minus
 * the English summary text itself: {@code eventType},
 * {@code importanceScore}, {@code sectorTags}, {@code tickerTags},
 * {@code actionableFor}.
 *
 * <p>Round-15c rationale: pre-round-15c the
 * {@code disclosure_summary} row was only created when an agent
 * actually paid for a summary, which under the round-11 lazy-generation
 * model produced exactly zero new cached rows for any filing since the
 * round-12 self-test era (the funnel review on 2026-05-28 showed paid
 * call streak at 21 days). The free {@code /recent} feed therefore
 * carried no AI metadata at all on freshly-ingested filings —
 * defeating the round-15b enrichment surface. This classifier writes a
 * stub row at ingestion so {@code /recent} can rank-order filings by
 * importance from day one, without spending a cent on LLM calls.
 *
 * <p>The rules were extracted from the 6,211 LLM-generated summaries
 * already in production cache as of 2026-05-29 — specifically the
 * {@code (report_nm, event_type, importance_score)} distribution
 * across that data, joined back to {@code disclosure}. The dominant
 * patterns ({@code 단일판매ㆍ공급계약체결} → {@code SUPPLY_CONTRACT_SIGNED},
 * {@code 주요사항보고서(유상증자결정)} → {@code RIGHTS_OFFERING}, etc.)
 * map deterministically because DART itself imposes a controlled
 * vocabulary on {@code report_nm}.
 *
 * <p>What the classifier cannot do:
 * <ul>
 *   <li>Free-form English summary text. That is and stays the paid
 *       product surfaced by the {@code @X402Paywall} endpoints.</li>
 *   <li>Fine-grained importance nuance — the LLM occasionally bumps
 *       importance from the eventType default based on the body text
 *       (e.g. dividend size, lawsuit damage amount). The classifier
 *       always returns the event-type median, which the LLM may
 *       later overlay on the same row via
 *       {@link DisclosureSummary#overlayLlmSummary}.</li>
 *   <li>Novel filing types not present in the 6,211-row training
 *       set. Those fall through to {@link #FALLBACK_EVENT_TYPE} —
 *       the LLM still handles them correctly on a paid call.</li>
 * </ul>
 *
 * <p>Stateless and side-effect-free: every method is static so the
 * classifier can be called from the polling scheduler's transaction
 * without DI or a Spring bean.
 */
public final class DisclosureClassifier {

    /** Returned when no rule matches the {@code report_nm}. The
     *  LLM-side {@code event_type} taxonomy uses the same string. */
    public static final String FALLBACK_EVENT_TYPE = "OTHER";

    /** Default importance when no rule matches. Falls right in the
     *  middle of the 1–10 scale — neither hiding nor over-promoting
     *  unknown filings. */
    public static final int FALLBACK_IMPORTANCE = 3;

    private DisclosureClassifier() {
    }

    /**
     * Output bundle for one classification — mirrors the subset of
     * {@code DisclosureSummary} fields the rules can populate.
     * {@code sectorTags} is intentionally empty in v1: KRX sector
     * data is not yet joined into the ingestion path. Adding it is
     * a future round.
     */
    public record Classification(
            String eventType,
            int importanceScore,
            List<String> sectorTags,
            List<String> tickerTags,
            List<String> actionableFor
    ) {
    }

    /**
     * Classify a filing by its DART {@code report_nm} and optional
     * KRX ticker. {@code reportNm} is required; {@code ticker} may
     * be null (the filing's company has not been resolved to a
     * listed ticker — common for delisted or non-listed filers).
     *
     * <p>Always returns a non-null {@link Classification} — falls
     * back to {@code OTHER} / importance 3 / no sector tags when no
     * rule matches. Callers can detect the fallback by comparing
     * {@code result.eventType()} to {@link #FALLBACK_EVENT_TYPE}.
     */
    public static Classification classify(String reportNm, String ticker) {
        if (reportNm == null || reportNm.isBlank()) {
            return defaultResult(ticker);
        }
        // DART prepends [기재정정] / [첨부정정] to filings that revise
        // an earlier submission. The classification of the inner
        // content stays the same — a revised dividend decision is
        // still a dividend decision — so strip the prefix for
        // matching and only fall back to AMENDMENT when no inner
        // rule matches.
        boolean isRevision = REVISION_PREFIX.matcher(reportNm).find();
        String stripped = REVISION_PREFIX.matcher(reportNm).replaceFirst("");

        for (Rule rule : RULES) {
            if (rule.matches(stripped)) {
                return new Classification(
                        rule.eventType(),
                        rule.defaultImportance(),
                        List.of(),
                        ticker == null ? List.of() : List.of(ticker),
                        rule.actionableFor()
                );
            }
        }

        if (isRevision) {
            // No inner rule matched — surface as a generic AMENDMENT
            // so analytics can split revisions from genuinely
            // un-classified filings.
            return new Classification(
                    "AMENDMENT",
                    3,
                    List.of(),
                    ticker == null ? List.of() : List.of(ticker),
                    List.of("traders")
            );
        }
        return defaultResult(ticker);
    }

    private static Classification defaultResult(String ticker) {
        return new Classification(
                FALLBACK_EVENT_TYPE,
                FALLBACK_IMPORTANCE,
                List.of(),
                ticker == null ? List.of() : List.of(ticker),
                List.of("traders")
        );
    }

    /**
     * Prefix DART uses for revisions of an earlier filing. Caught
     * loosely (the inner brackets may carry any of a small set of
     * codes) so the rule body matches against the substantive
     * portion of the title.
     */
    private static final Pattern REVISION_PREFIX = Pattern.compile("^\\[(기재정정|첨부정정|발행조건확정|첨부추가)\\]");

    /**
     * Single classification rule. Order in {@link #RULES} matters —
     * the first match wins, so more-specific rules must come before
     * the generic ones they would otherwise be subsumed by (e.g.
     * {@code 자산유동화} must come before {@code 증권발행}, and the
     * {@code 주요사항보고서(...)} family must come before the bare
     * {@code 사채} family).
     *
     * <p>The {@code keyword} field is a substring match — fast, no
     * regex. The 6,211 LLM-classified rows used to derive these
     * patterns all carry the keyword verbatim in the {@code
     * report_nm} string; we did not need regex backtracking.
     */
    private record Rule(
            String keyword,
            String eventType,
            int defaultImportance,
            List<String> actionableFor
    ) {
        boolean matches(String reportNm) {
            return reportNm.contains(keyword);
        }
    }

    /**
     * Rule table. Importance defaults are medians extracted from
     * the production {@code disclosure_summary} table on 2026-05-29
     * — see the round-15c commit message for the exact SQL. The
     * {@code actionableFor} buckets follow the same taxonomy the
     * LLM uses ({@code traders}, {@code long_term_investors},
     * {@code creditors}, {@code regulators}).
     *
     * <p>Order: high-severity / specific patterns first so they win
     * over the generic {@code 주요사항보고서(...)} keyword.
     */
    private static final List<Rule> RULES = List.of(
            // --- Very high severity (importance 9) ---
            new Rule("상장폐지", "DELISTING", 9, List.of("traders", "long_term_investors", "creditors")),
            new Rule("회생절차개시", "BANKRUPTCY", 9, List.of("traders", "long_term_investors", "creditors")),
            new Rule("해산사유발생", "BANKRUPTCY", 9, List.of("traders", "long_term_investors", "creditors")),
            new Rule("사채원리금미지급", "BOND_DEFAULT", 9, List.of("creditors", "long_term_investors")),
            new Rule("상장채권기한의이익상실", "DEBT_DEFAULT", 9, List.of("creditors")),
            new Rule("주권매매거래정지해제", "TRADING_RESUMPTION", 5, List.of("traders")),
            new Rule("주권매매거래정지", "TRADING_SUSPENSION", 9, List.of("traders")),
            new Rule("회사합병결정", "MERGER", 9, List.of("traders", "long_term_investors")),
            new Rule("합병등종료보고서(합병)", "MERGER", 9, List.of("traders", "long_term_investors")),

            // --- High severity (importance 7) ---
            new Rule("회사분할결정", "SPIN_OFF", 5, List.of("traders", "long_term_investors")),
            new Rule("합병등종료보고서(분할)", "SPIN_OFF", 5, List.of("traders", "long_term_investors")),
            new Rule("최대주주변경을수반하는주식담보제공", "CONTROL_CHANGE_PLEDGE", 7, List.of("traders", "long_term_investors")),
            new Rule("최대주주변경을수반하는주식양수도", "CONTROL_CHANGE_PLEDGE", 7, List.of("traders", "long_term_investors")),
            new Rule("최대주주변경", "CONTROL_CHANGE", 7, List.of("traders", "long_term_investors")),
            new Rule("대표이사변경", "CEO_CHANGE", 7, List.of("traders", "long_term_investors")),
            new Rule("대표이사(대표집행임원)변경", "CEO_CHANGE", 7, List.of("traders", "long_term_investors")),
            new Rule("타법인주식및출자증권취득", "ACQUISITION", 7, List.of("traders", "long_term_investors")),
            new Rule("비유동자산취득", "ACQUISITION", 5, List.of("traders", "long_term_investors")),
            new Rule("주요사항보고서(유상증자결정)", "RIGHTS_OFFERING", 7, List.of("traders", "long_term_investors")),
            new Rule("특수관계인의유상증자참여", "RIGHTS_OFFERING", 7, List.of("traders", "long_term_investors")),
            new Rule("주요사항보고서(전환사채권발행결정)", "CONVERTIBLE_BOND_ISSUANCE", 7, List.of("traders", "long_term_investors")),
            new Rule("주요사항보고서(자기주식취득결정)", "TREASURY_STOCK_ACQUISITION", 7, List.of("traders", "long_term_investors")),
            new Rule("자기주식취득결과보고서", "TREASURY_STOCK_ACQUISITION", 5, List.of("traders", "long_term_investors")),
            new Rule("주요사항보고서(자기주식처분결정)", "TREASURY_STOCK_DISPOSAL", 6, List.of("traders", "long_term_investors")),
            new Rule("자기주식처분결과보고서", "TREASURY_STOCK_DISPOSAL", 6, List.of("traders", "long_term_investors")),
            new Rule("주식소각결정", "TREASURY_STOCK_CANCELLATION", 5, List.of("traders", "long_term_investors")),
            new Rule("소송등의제기", "LITIGATION", 7, List.of("traders", "long_term_investors")),
            new Rule("소송등의판결ㆍ결정", "LITIGATION", 5, List.of("traders", "long_term_investors")),

            // --- Medium severity (importance 5) ---
            new Rule("자산유동화", "ASSET_BACKED_SECURITIES", 5, List.of("creditors")),
            new Rule("유동화증권", "ASSET_BACKED_SECURITIES", 5, List.of("creditors")),
            new Rule("현금ㆍ현물배당결정", "DIVIDEND_DECISION", 5, List.of("traders", "long_term_investors")),
            new Rule("연결재무제표기준영업(잠정)실적", "PRELIMINARY_RESULTS", 5, List.of("traders", "long_term_investors")),
            new Rule("영업(잠정)실적", "PRELIMINARY_RESULTS", 5, List.of("traders", "long_term_investors")),
            new Rule("결산실적공시예고", "EARNINGS_PREVIEW", 5, List.of("traders", "long_term_investors")),
            new Rule("단일판매ㆍ공급계약체결", "SUPPLY_CONTRACT_SIGNED", 5, List.of("traders")),
            new Rule("단일판매ㆍ공급계약해지", "SUPPLY_CONTRACT_TERMINATED", 5, List.of("traders")),
            new Rule("특수관계인으로부터자금차입", "RELATED_PARTY_TRANSACTION", 5, List.of("traders", "long_term_investors")),
            new Rule("특수관계인에대한자금대여", "RELATED_PARTY_TRANSACTION", 5, List.of("traders", "long_term_investors")),
            new Rule("동일인등출자계열회사와의상품ㆍ용역거래", "RELATED_PARTY_TRANSACTION", 5, List.of("traders", "long_term_investors")),
            new Rule("타인에대한채무보증결정", "DEBT_GUARANTEE", 5, List.of("creditors")),
            new Rule("타인에대한담보제공", "SHARE_PLEDGE", 5, List.of("creditors")),
            new Rule("특수관계인에대한담보제공", "SHARE_PLEDGE", 5, List.of("creditors")),
            new Rule("전환청구권행사", "CONVERTIBLE_BOND_CONVERSION", 5, List.of("traders")),
            new Rule("교환청구권행사", "CONVERTIBLE_BOND_CONVERSION", 5, List.of("traders")),
            new Rule("전환가액", "CONVERSION_PRICE_ADJUSTMENT", 5, List.of("traders")),
            new Rule("신주인수권행사가액", "CONVERSION_PRICE_ADJUSTMENT", 5, List.of("traders")),
            new Rule("교환가액의조정", "CONVERSION_PRICE_ADJUSTMENT", 5, List.of("traders")),
            new Rule("전환사채(해외전환사채포함)발행후만기전사채취득", "BOND_REDEMPTION", 5, List.of("creditors")),
            new Rule("자기전환사채만기전취득결정", "BOND_REDEMPTION", 5, List.of("creditors")),
            new Rule("자기교환사채만기전취득결정", "BOND_REDEMPTION", 5, List.of("creditors")),
            new Rule("증권발행실적보고서", "DEBT_ISSUANCE", 5, List.of("creditors")),
            new Rule("일괄신고추가서류", "DEBT_ISSUANCE", 5, List.of("creditors")),
            new Rule("투자설명서", "PROSPECTUS_FILING", 5, List.of("traders")),
            new Rule("기업가치제고계획", "MATERIAL_EVENT", 5, List.of("long_term_investors")),
            new Rule("투자판단관련주요경영사항", "MATERIAL_EVENT", 5, List.of("traders", "long_term_investors")),
            new Rule("감자완료", "STOCK_CONSOLIDATION", 5, List.of("traders")),
            new Rule("주식병합결정", "STOCK_CONSOLIDATION", 5, List.of("traders")),
            new Rule("주식분할결정", "STOCK_SPLIT", 4, List.of("traders")),
            new Rule("권리락", "BONUS_ISSUANCE", 5, List.of("traders")),
            new Rule("주식매수선택권부여", "STOCK_OPTION_GRANT", 5, List.of("traders")),
            new Rule("주식매수선택권행사", "STOCK_OPTION_EXERCISE", 5, List.of("traders")),
            new Rule("영업양수", "BUSINESS_TRANSFER", 5, List.of("traders", "long_term_investors")),
            new Rule("영업양도", "BUSINESS_TRANSFER", 5, List.of("traders", "long_term_investors")),
            new Rule("비유동자산처분", "ASSET_DISPOSAL", 5, List.of("traders", "long_term_investors")),
            new Rule("공개매수결과보고서", "PUBLIC_TENDER_OFFER_RESULT", 5, List.of("traders", "long_term_investors")),
            new Rule("최대주주등소유주식변동", "MAJOR_SHAREHOLDER_TRANSACTION", 5, List.of("traders")),
            new Rule("최대주주등의주식보유변동", "MAJOR_SHAREHOLDER_TRANSACTION", 5, List.of("traders")),

            // --- Lower-mid severity (importance 3) ---
            new Rule("기업설명회(IR)개최", "IR_EVENT", 3, List.of("traders", "long_term_investors")),
            new Rule("주주총회소집공고", "SHAREHOLDERS_MEETING", 3, List.of("traders", "long_term_investors")),
            new Rule("주주총회소집결의", "SHAREHOLDERS_MEETING", 3, List.of("traders", "long_term_investors")),
            new Rule("의결권대리행사권유", "SHAREHOLDERS_MEETING", 3, List.of("traders", "long_term_investors")),
            new Rule("임원ㆍ주요주주특정증권등소유상황", "MAJOR_SHAREHOLDER_FILING", 3, List.of("traders")),
            new Rule("주식등의대량보유상황보고서", "MAJOR_SHAREHOLDER_FILING", 3, List.of("traders")),
            new Rule("사외이사의선임ㆍ해임", "BOARD_CHANGE", 3, List.of("long_term_investors")),
            new Rule("소속부변경", "MATERIAL_EVENT", 3, List.of("traders")),

            // --- Routine / low importance (1) ---
            new Rule("연결감사보고서", "AUDIT_REPORT", 1, List.of("long_term_investors")),
            new Rule("감사보고서", "AUDIT_REPORT", 1, List.of("long_term_investors")),
            new Rule("분기보고서", "PERIODIC_REPORT", 1, List.of("long_term_investors")),
            new Rule("반기보고서", "PERIODIC_REPORT", 1, List.of("long_term_investors")),
            new Rule("사업보고서", "PERIODIC_REPORT", 1, List.of("long_term_investors")),
            new Rule("주주명부폐쇄기간또는기준일설정", "RECORD_DATE_NOTICE", 1, List.of("traders")),
            new Rule("주주명부폐쇄(기준일)결정", "RECORD_DATE_NOTICE", 1, List.of("traders")),
            // Korea Fair Trade Commission chaebol-structure annual /
            // quarterly disclosure. Showed up as the dominant OTHER
            // bucket in the first 10 minutes of live classifier
            // traffic (5 of 8 rows). Listed at low importance — the
            // 6,211-row LLM cache shows three CONGLOMERATE_DISCLOSURE
            // examples averaging importance 3.
            new Rule("대규모기업집단현황공시", "CONGLOMERATE_DISCLOSURE", 3, List.of("long_term_investors")),

            // --- Capital structure (importance 5) — checked late so
            //     the more-specific 주요사항보고서(감자결정) wins above.
            new Rule("주요사항보고서(감자결정)", "CAPITAL_REDUCTION", 5, List.of("traders", "long_term_investors")),
            new Rule("효력발생안내", "OTHER", 3, List.of("traders"))
    );
}
