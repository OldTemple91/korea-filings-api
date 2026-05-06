package com.dartintel.api.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Targeted coverage for {@link X402PaywallInterceptor#sanitiseLogValue}.
 *
 * <p>Ensures the strip behaviour covers the full C0 control range,
 * the ANSI escape introducer, and DEL — not just CR/LF/TAB. A
 * compromised facilitator returning an {@code invalidReason} that
 * embeds those characters used to slip through and forge log entries
 * downstream (terminal log viewers render ANSI as colour, structured
 * log shippers like Logstash crash on null bytes).
 *
 * <p>Control characters below are constructed via numeric (char)
 * casts in source so they survive any transit / editor that strips
 * raw control bytes — a backslash-u escape inside this Javadoc would
 * be mistreated by Java's pre-processor.
 */
class X402PaywallInterceptorSanitiseTest {

    private static final char NUL = (char) 0x00;
    private static final char BS  = (char) 0x08;
    private static final char VT  = (char) 0x0b;
    private static final char FF  = (char) 0x0c;
    private static final char ESC = (char) 0x1b;
    private static final char DEL = (char) 0x7f;

    @Test
    void replacesNullByte() {
        String forged = "foo" + NUL + "bar";
        String sanitised = X402PaywallInterceptor.sanitiseLogValue(forged);
        assertThat(sanitised).isEqualTo("foo bar");
    }

    @Test
    void replacesAnsiEscapeIntroducer() {
        // ESC[31mERROR ESC[0m would render as red "ERROR" in a
        // terminal viewer if the escape byte survives. Both ESC bytes
        // must go.
        String forged = "" + ESC + "[31mfake-admin 402" + ESC + "[0m";
        String sanitised = X402PaywallInterceptor.sanitiseLogValue(forged);
        // The square brackets and digits themselves stay (printable
        // ASCII); only the escape character that gives them meaning
        // is gone, so the line renders as plain text.
        assertThat(sanitised).doesNotContain(String.valueOf(ESC));
        assertThat(sanitised).contains("[31mfake-admin 402");
    }

    @Test
    void replacesDelByte() {
        String forged = "a" + DEL + "b";
        String sanitised = X402PaywallInterceptor.sanitiseLogValue(forged);
        assertThat(sanitised).isEqualTo("a b");
    }

    @Test
    void replacesCarriageReturnLineFeedAndTab() {
        // The pre-existing cases the original implementation already
        // covered. Lock them in so a regression to a narrower charset
        // gets caught.
        assertThat(X402PaywallInterceptor.sanitiseLogValue("line1\r\nline2"))
                .isEqualTo("line1  line2");
        assertThat(X402PaywallInterceptor.sanitiseLogValue("a\tb"))
                .isEqualTo("a b");
    }

    @Test
    void replacesBackspaceVerticalTabFormFeed() {
        // Three more C0 control characters that the old
        // [\\r\\n\\t]-only regex let through but a determined
        // log-injection attempt could use to overwrite preceding
        // characters in some terminal viewers (BS) or break
        // structured log parsers (FF, VT).
        String forged = "a" + BS + "b" + FF + "c" + VT + "d";
        String sanitised = X402PaywallInterceptor.sanitiseLogValue(forged);
        assertThat(sanitised).doesNotContain(String.valueOf(BS));
        assertThat(sanitised).doesNotContain(String.valueOf(FF));
        assertThat(sanitised).doesNotContain(String.valueOf(VT));
        assertThat(sanitised).isEqualTo("a b c d");
    }

    @Test
    void preservesPrintableAscii() {
        String clean = "Forbidden: invalid signature for 0x254A42D7c617B38c7B43186e892d3af4bf9D6c44";
        assertThat(X402PaywallInterceptor.sanitiseLogValue(clean)).isEqualTo(clean);
    }

    @Test
    void preservesNonAsciiUnicodeAboveControlRange() {
        // Korean / em dash / euro / emoji should pass through unchanged
        // — only the C0 + DEL range is stripped.
        String unicode = "삼성전자 공시 — €100 ✅";
        assertThat(X402PaywallInterceptor.sanitiseLogValue(unicode)).isEqualTo(unicode);
    }

    @Test
    void nullAndEmptyReturnUnknown() {
        assertThat(X402PaywallInterceptor.sanitiseLogValue(null)).isEqualTo("unknown");
        assertThat(X402PaywallInterceptor.sanitiseLogValue("")).isEqualTo("unknown");
    }
}
