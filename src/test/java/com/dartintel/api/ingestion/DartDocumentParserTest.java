package com.dartintel.api.ingestion;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DartDocumentParserTest {

    private final DartDocumentParser parser = new DartDocumentParser();

    @Test
    void extractsTextFromHtmlEntry() throws Exception {
        byte[] zip = zipOf(
                "filing.html",
                "<html><body>"
                        + "<h1>주요사항보고서</h1>"
                        + "<p>발행대상: 보통주 1,234,567주</p>"
                        + "<p>발행가액: 주당 8,500원</p>"
                        + "</body></html>");

        String body = parser.parse(zip);

        assertThat(body)
                .contains("주요사항보고서")
                .contains("발행대상: 보통주 1,234,567주")
                .contains("발행가액: 주당 8,500원");
        // jsoup .text() collapses HTML structure; no angle brackets remain.
        assertThat(body).doesNotContain("<").doesNotContain(">");
    }

    @Test
    void extractsTextFromXbrlXmlEntry() throws Exception {
        byte[] zip = zipOf(
                "filing.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <report xmlns:dart="http://dart.fss.or.kr">
                  <dart:section>
                    <dart:title>유상증자 결정</dart:title>
                    <dart:value>신주발행 가액 100억원</dart:value>
                  </dart:section>
                </report>
                """);

        String body = parser.parse(zip);

        assertThat(body)
                .contains("유상증자 결정")
                .contains("신주발행 가액 100억원");
    }

    @Test
    void concatenatesMultipleTextEntries() throws Exception {
        byte[] zip = zipOf(new ZipEntryFixture("part1.html", "<p>첫번째 섹션</p>"),
                new ZipEntryFixture("part2.html", "<p>두번째 섹션</p>"));

        String body = parser.parse(zip);

        assertThat(body).contains("첫번째 섹션").contains("두번째 섹션");
    }

    @Test
    void ignoresImageAttachmentEntries() throws Exception {
        // Images alongside markup are common (signed-page scans, charts).
        // Their bytes should be skipped without error rather than fed to
        // jsoup as garbage.
        byte[] zip = zipOf(
                new ZipEntryFixture("filing.html", "<p>본문 내용</p>"),
                new ZipEntryFixture("signature.jpg", "binary-image-bytes"),
                new ZipEntryFixture("chart.png", "more-binary-bytes"));

        String body = parser.parse(zip);

        assertThat(body).contains("본문 내용");
        assertThat(body).doesNotContain("binary").doesNotContain("png");
    }

    @Test
    void truncatesAtMaxBodyChars() throws Exception {
        // Build an HTML body whose plain text comfortably exceeds the
        // 20,000-char cap. jsoup will return the inner text verbatim.
        StringBuilder hugeText = new StringBuilder();
        while (hugeText.length() < DartDocumentParser.MAX_BODY_CHARS + 5_000) {
            hugeText.append("회사는 신규 시설투자를 위해 자금을 조달한다. ");
        }
        byte[] zip = zipOf("filing.html",
                "<html><body><p>" + hugeText + "</p></body></html>");

        String body = parser.parse(zip);

        assertThat(body).hasSize(DartDocumentParser.MAX_BODY_CHARS);
    }

    @Test
    void normalisesRunsOfWhitespaceToSingleSpaces() throws Exception {
        byte[] zip = zipOf("filing.html",
                "<p>줄1</p>\n\n\n<p>줄2</p>\t\t<p>줄3</p>");

        String body = parser.parse(zip);

        assertThat(body).doesNotContain("\n").doesNotContain("\t");
        assertThat(body).doesNotContain("  "); // no double spaces
    }

    @Test
    void emptyZipReturnsEmptyString() throws Exception {
        byte[] zip = zipOf();

        String body = parser.parse(zip);

        assertThat(body).isEmpty();
    }

    @Test
    void zipWithOnlyImageEntriesReturnsEmptyString() throws Exception {
        byte[] zip = zipOf(
                new ZipEntryFixture("a.jpg", "img1"),
                new ZipEntryFixture("b.png", "img2"));

        String body = parser.parse(zip);

        assertThat(body).isEmpty();
    }

    @Test
    void nullOrEmptyBytesReturnEmptyString() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse(new byte[0])).isEmpty();
    }

    @Test
    void truncatedZipBytesRaiseIllegalState() {
        // Local-file-header magic (PK\03\04) followed by a partial header
        // — declares filename "hi.ht" (5 bytes) but the entry body is
        // missing entirely. ZipInputStream reads past EOF and the catch
        // block in parse() wraps the IOException as IllegalStateException.
        byte[] truncatedZip = new byte[]{
                0x50, 0x4B, 0x03, 0x04,
                0x14, 0x00, 0x00, 0x00,
                0x08, 0x00,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                0x05, 0x00,
                0x00, 0x00,
                'h', 'i', '.', 'h', 't'
        };

        assertThatThrownBy(() -> parser.parse(truncatedZip))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to unzip");
    }

    @Test
    void nonZipBytesReturnEmptyString() {
        // ZipInputStream is lenient when the magic bytes don't match —
        // it silently returns zero entries rather than throwing. The
        // parser passes that through as an empty body. Documenting the
        // behavior so a future "make this throw" change has a failing
        // test to break.
        byte[] notAZip = "this is not a zip archive".getBytes(StandardCharsets.UTF_8);

        assertThat(parser.parse(notAZip)).isEmpty();
    }

    // ----- helpers -----

    private record ZipEntryFixture(String name, String content) {
    }

    private static byte[] zipOf(String entryName, String content) throws Exception {
        return zipOf(new ZipEntryFixture(entryName, content));
    }

    private static byte[] zipOf(ZipEntryFixture... entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipEntryFixture entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
