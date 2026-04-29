package com.dartintel.api.company;

import com.dartintel.api.ingestion.DartClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Reads the Korean listed-company directory from DART and keeps it
 * fresh.
 *
 * <p>The {@code corpCode.xml} dump is the canonical source for every
 * filing entity registered with the FSS — listed, delisted, foreign,
 * non-corporate. It is published as a single XML file that DART
 * regenerates daily; we sync the entire dump on a 24-hour cadence
 * because the ~85k-row file is small and a row-level diff fetch is
 * not exposed.
 *
 * <p>Only entities with a non-blank {@code stock_code} are persisted —
 * those are the ~2.5k currently listed on KOSPI / KOSDAQ / KONEX.
 * The rest are not addressable by ticker and are not useful to the
 * by-ticker endpoint.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyService {

    private static final DateTimeFormatter DART_DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final CompanyRepository repository;
    private final DartClient dartClient;

    /**
     * Fetch the current corpCode dump and upsert every listed-company
     * row. Returns the number of rows inserted/updated for logging.
     * Idempotent.
     */
    @Transactional
    public int syncDirectory() {
        long started = System.nanoTime();
        byte[] xml = dartClient.fetchCorpCodeXml();
        List<Row> rows = parseCorpCodeXml(xml);
        log.info("DART corpCode dump parsed: {} total entries, {} listed",
                rows.size(),
                rows.stream().filter(r -> r.ticker != null).count());

        Map<String, Company> existing = new HashMap<>();
        repository.findAll().forEach(c -> existing.put(c.getTicker(), c));

        int upserted = 0;
        for (Row r : rows) {
            if (r.ticker == null) {
                continue; // skip unlisted entries
            }
            Company company = existing.get(r.ticker);
            if (company == null) {
                repository.save(new Company(
                        r.ticker, r.corpCode, r.nameKr, r.nameEn,
                        guessMarket(r.ticker), r.modifyDate));
            } else {
                company.update(r.nameKr, r.nameEn, guessMarket(r.ticker), r.modifyDate);
            }
            upserted++;
        }
        long ms = (System.nanoTime() - started) / 1_000_000L;
        log.info("Company sync complete: {} listed companies upserted in {} ms", upserted, ms);
        return upserted;
    }

    /**
     * Best-effort market classification from the ticker prefix. The
     * KRX no longer publishes a clean ticker-prefix → market mapping,
     * but the rough convention is:
     *   KOSPI   ticks under 200000 (and a handful of legacy 9xxxxx)
     *   KOSDAQ  most 0xxxxx + 1xxxxx + 2xxxxx (post-2020 listings)
     *   KONEX   3-digit-suffix special blocks
     * For the agent UX it's enough to surface a "this is KOSPI vs
     * KOSDAQ" hint; the strict market identifier comes from upstream
     * if a caller really needs it.
     */
    private static String guessMarket(String ticker) {
        if (ticker == null) return null;
        // Heuristic: KOSPI stocks below ticker 200000 are reliably KOSPI;
        // anything above is more likely KOSDAQ. Edge cases exist but the
        // field is advisory not authoritative.
        try {
            int n = Integer.parseInt(ticker);
            if (n < 200000) return "KOSPI";
            if (n < 600000) return "KOSDAQ";
            return "OTHER";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public List<Company> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return repository.search(query.trim(), PageRequest.of(0, Math.min(Math.max(limit, 1), 50)));
    }

    public Optional<Company> findByTicker(String ticker) {
        return repository.findById(ticker);
    }

    public Optional<Company> findByCorpCode(String corpCode) {
        return repository.findByCorpCode(corpCode);
    }

    /**
     * SAX-equivalent parse via DOM for simplicity — the file is ~30 MB
     * uncompressed which is well under the 64 MB DocumentBuilder
     * default. If the dump grows past that we'll switch to StAX
     * streaming.
     */
    private static List<Row> parseCorpCodeXml(byte[] xml) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            // Disable external entity expansion for safety against
            // billion-laughs / XXE on the (admittedly trusted) DART
            // source.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            NodeList list = doc.getElementsByTagName("list");
            List<Row> rows = new ArrayList<>(list.getLength());
            for (int i = 0; i < list.getLength(); i++) {
                Element el = (Element) list.item(i);
                String corpCode = textOrNull(el, "corp_code");
                String corpName = textOrNull(el, "corp_name");
                String corpEng = textOrNull(el, "corp_eng_name");
                String stock = textOrNull(el, "stock_code");
                String modify = textOrNull(el, "modify_date");
                if (corpCode == null || corpName == null) {
                    continue;
                }
                LocalDate modifyDate = modify != null && !modify.isBlank()
                        ? LocalDate.parse(modify, DART_DATE_FMT)
                        : LocalDate.now();
                String ticker = (stock != null && !stock.isBlank()) ? stock.trim() : null;
                rows.add(new Row(corpCode.trim(), corpName.trim(),
                        corpEng != null ? corpEng.trim() : null,
                        ticker, modifyDate));
            }
            return rows;
        } catch (ParserConfigurationException | SAXException | java.io.IOException e) {
            throw new IllegalStateException("Failed to parse DART corpCode.xml", e);
        }
    }

    private static String textOrNull(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return null;
        String text = nodes.item(0).getTextContent();
        return text != null ? text : null;
    }

    private record Row(String corpCode, String nameKr, String nameEn,
                       String ticker, LocalDate modifyDate) {
    }
}
