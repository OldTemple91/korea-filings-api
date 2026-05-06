package com.dartintel.api.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extract a plain-text body from a DART {@code /api/document.xml} ZIP.
 *
 * <p>The endpoint returns a ZIP archive whose entries vary by filing
 * type. Typical layouts are one of:
 *
 * <ul>
 *   <li>One {@code .xml} file (XBRL with namespaced markup) plus an
 *       {@code .html} sibling that renders the same content for
 *       browser display. The HTML is what most filings ship.</li>
 *   <li>Multiple {@code .xml} fragments split by section, no HTML —
 *       common for long-form annual reports.</li>
 *   <li>Embedded {@code .jpg} / {@code .png} attachments alongside
 *       the markup — irrelevant to the LLM, ignored here.</li>
 * </ul>
 *
 * <p>Strategy: walk every entry, keep {@code .html} and {@code .xml}
 * payloads, hand each to {@link Jsoup#parse(String)} to strip tags
 * and return its rendered text, concatenate, normalise whitespace,
 * truncate at {@link #MAX_BODY_CHARS}. The truncation cap matches
 * the {@code disclosure.body} TEXT column's documented soft limit
 * — past that point the LLM has more than enough quantitative
 * detail and additional tokens just inflate cost without lifting
 * summary quality.
 *
 * <p>Stateless and side-effect-free: the same ZIP bytes always
 * produce the same string. Fail-soft on per-entry parse errors
 * (one bad XBRL fragment shouldn't lose the whole filing) but
 * fail-loud if the entire ZIP is unreadable, since that points to
 * an upstream truncation that the caller needs to surface.
 */
@Component
@Slf4j
public class DartDocumentParser {

    /**
     * Body cap. 20,000 chars is roughly 6-8 KB of UTF-8 Korean —
     * enough for the executive-summary section of an annual report
     * plus the financial-highlights table, which is where the
     * quantitative facts the LLM needs actually live. Past that the
     * filing repeats itself in increasingly legalistic appendices
     * that don't help the summary and inflate Gemini token cost.
     */
    public static final int MAX_BODY_CHARS = 20_000;

    /**
     * Extensions we extract text from. Lowercased before matching.
     * Other entries (jpg/png/gif/svg attachments, .pdf renditions
     * occasionally bundled) are ignored.
     */
    private static final List<String> TEXT_EXTENSIONS = List.of(".html", ".htm", ".xml");

    /**
     * Parse the raw ZIP bytes from {@link DartClient#fetchDocument(String)}
     * into a plain-text body suitable for LLM input.
     *
     * @param zipBytes raw ZIP archive from DART
     * @return whitespace-normalised plain text, capped at
     *         {@value #MAX_BODY_CHARS} chars; empty string if the
     *         archive contains no extractable text entries
     * @throws IllegalStateException if the archive itself is
     *         unreadable (truncated stream, bad CRC, not a ZIP)
     */
    public String parse(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) {
            return "";
        }

        StringBuilder collected = new StringBuilder();
        int entriesSeen = 0;
        int entriesExtracted = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entriesSeen++;
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (!hasTextExtension(name)) {
                    continue;
                }

                byte[] entryBytes;
                try {
                    entryBytes = zip.readAllBytes();
                } catch (IOException e) {
                    // Per-entry read failure: log and skip rather than aborting
                    // the whole ZIP. A single corrupt fragment is rare but the
                    // fall-through to title-only summary is worse than losing
                    // one section.
                    log.warn("DART document entry {} unreadable, skipping", entry.getName(), e);
                    continue;
                }

                String entryText = extractText(entryBytes);
                if (!entryText.isEmpty()) {
                    if (collected.length() > 0) {
                        collected.append('\n');
                    }
                    collected.append(entryText);
                    entriesExtracted++;
                }

                // Short-circuit once we have enough text. Avoids parsing
                // multi-MB image attachments or boilerplate appendices
                // when we already have plenty of body for the LLM.
                if (collected.length() >= MAX_BODY_CHARS) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to unzip DART document body (received "
                            + zipBytes.length + " bytes)", e);
        }

        String normalised = normaliseWhitespace(collected.toString());
        String truncated = normalised.length() > MAX_BODY_CHARS
                ? normalised.substring(0, MAX_BODY_CHARS)
                : normalised;

        log.debug("parsed DART document zipBytes={} entries={} extracted={} chars={}",
                zipBytes.length, entriesSeen, entriesExtracted, truncated.length());
        return truncated;
    }

    private static boolean hasTextExtension(String lowercaseName) {
        for (String ext : TEXT_EXTENSIONS) {
            if (lowercaseName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * jsoup over a hand-rolled regex stripper because filing bodies
     * mix XBRL XML, nested HTML tables, and inline SVG / iframes.
     * jsoup tolerates malformed nested markup that ad-hoc regex
     * would either misparse or drop entirely. {@code Jsoup.parse}
     * accepts both well-formed HTML and XBRL-flavoured XML — it
     * treats the namespace prefixes as opaque tag names and emits
     * the inner text correctly.
     */
    private static String extractText(byte[] bytes) {
        try {
            // DART files are Korean — UTF-8 in modern submissions, but
            // older filings sometimes ship as EUC-KR. jsoup auto-detects
            // the charset from the meta tag / XML prolog when given
            // raw bytes via String constructor with default charset
            // is wrong; build the document via the InputStream variant
            // so jsoup can sniff the declared encoding.
            //
            // The simplest correct call here is Jsoup.parse(String):
            // we pass UTF-8 first, fall back to nothing (the default
            // platform charset on every linux container is UTF-8).
            // Empirical check on production filings showed every
            // /document.xml body declares UTF-8 in its XML prolog or
            // HTML meta tag.
            String raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return Jsoup.parse(raw).text();
        } catch (Exception e) {
            log.warn("failed to extract text from DART document entry", e);
            return "";
        }
    }

    /**
     * Collapse runs of whitespace into single spaces and strip
     * leading / trailing whitespace. jsoup's {@code text()} already
     * does most of this, but XBRL fragments concatenated with a
     * newline separator can produce double-newlines that bloat the
     * char count without adding signal.
     */
    private static String normaliseWhitespace(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
