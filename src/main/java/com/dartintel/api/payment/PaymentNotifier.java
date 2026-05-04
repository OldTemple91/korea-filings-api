package com.dartintel.api.payment;

import com.dartintel.api.payment.dto.FacilitatorSettleResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Fires a Slack-shaped webhook ("text": "...") on every settled
 * payment so an operator hears about the first external paid call
 * without having to grep Postgres. Discord's incoming webhook URLs
 * accept the same JSON shape, so the same env var works for either.
 *
 * <p>Configured via {@code X402_NOTIFY_WEBHOOK_URL}. Empty / unset
 * disables the notifier entirely (current default — operator opts
 * in on a per-environment basis). Errors are logged at WARN and
 * never propagate; the payment flow must NOT depend on the
 * webhook being reachable.
 */
@Component
@Slf4j
public class PaymentNotifier {

    private final String webhookUrl;
    private final HttpClient http;

    public PaymentNotifier(@Value("${x402.notify.webhook-url:}") String webhookUrl) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.strip();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        if (this.webhookUrl.isEmpty()) {
            log.info("PaymentNotifier disabled (X402_NOTIFY_WEBHOOK_URL not set)");
        } else {
            log.info("PaymentNotifier enabled");
        }
    }

    /**
     * Best-effort fire-and-forget notification of a successful
     * settlement. {@link X402SettlementAdvice#persistPaymentLog}
     * calls this AFTER the payment_log row is committed (or after
     * the row failed to commit due to a Postgres outage —
     * {@code reconciliationFailure} flag distinguishes the two so
     * the operator can prioritise the unrecoverable case).
     */
    public void notifySettlement(VerifiedPayment verified,
                                 FacilitatorSettleResponse settle,
                                 BigDecimal amountUsdc,
                                 boolean reconciliationFailure) {
        if (webhookUrl.isEmpty()) {
            return;
        }
        try {
            String message = buildMessage(verified, settle, amountUsdc, reconciliationFailure);
            String payload = "{\"text\":\"" + jsonEscape(message) + "\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            // Async send — never block the request thread on the
            // webhook. The send() return is ignored; if the webhook
            // is down or slow, the payment flow is unaffected.
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        log.warn("PaymentNotifier webhook failed: {}", ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("PaymentNotifier failed to build/send: {}", e.getMessage());
        }
    }

    private static String buildMessage(VerifiedPayment verified,
                                       FacilitatorSettleResponse settle,
                                       BigDecimal amountUsdc,
                                       boolean reconciliationFailure) {
        String alarm = reconciliationFailure
                ? ":rotating_light: payment_log persist FAILED — manual reconciliation required"
                : ":moneybag: paid";
        String tx = settle.transaction() != null && !settle.transaction().isBlank()
                ? settle.transaction() : "(missing)";
        String txLink = tx.equals("(missing)") ? tx
                : "https://basescan.org/tx/" + tx;
        return alarm + " | " + amountUsdc.toPlainString() + " USDC | "
                + verified.endpoint() + " | "
                + "payer=" + verified.payer() + " | "
                + "tx=" + txLink;
    }

    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
