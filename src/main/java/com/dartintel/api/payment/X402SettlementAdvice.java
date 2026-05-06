package com.dartintel.api.payment;

import com.dartintel.api.payment.dto.FacilitatorSettleRequest;
import com.dartintel.api.payment.dto.FacilitatorSettleResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settles verified x402 payments and attaches the {@code PAYMENT-RESPONSE}
 * header (plus the legacy {@code X-PAYMENT-RESPONSE} alias) to the
 * outbound response before the body is written. Runs only for
 * controller methods annotated with {@link X402Paywall}; other
 * responses pass through untouched.
 *
 * <p>Running at {@link ResponseBodyAdvice#beforeBodyWrite} (rather
 * than
 * {@link org.springframework.web.servlet.HandlerInterceptor#afterCompletion})
 * is what makes the settlement header land on the wire — once Spring
 * has started streaming the @ResponseBody, headers are frozen.
 *
 * <p>PaymentLog persistence lives here too: it belongs with the
 * settlement transaction, and the request's {@link VerifiedPayment}
 * attribute gives every field needed.
 *
 * <h3>Fail-closed on settlement failure</h3>
 *
 * <p>If the facilitator's {@code /settle} call throws or returns a
 * non-success result, the controller's response body is dropped and
 * the response is rewritten to match the
 * <a href="https://github.com/coinbase/x402/blob/main/specs/transports-v2/http.md">
 * x402 v2 transport spec</a> failure shape:
 *
 * <pre>
 *   HTTP/1.1 402 Payment Required
 *   PAYMENT-RESPONSE: &lt;base64 of {"success":false,"errorReason":"...",...}&gt;
 *
 *   {}
 * </pre>
 *
 * <p>Returning the bought data while the merchant is unpaid would be
 * a non-revocable revenue leak — verify is binding, but settle is
 * what actually transfers funds, and a server that delivers data on a
 * failed settle is effectively giving it away whenever the
 * facilitator has an outage. Sending a 502 instead of the spec's 402
 * would also confuse range-of-the-mill x402 clients into treating the
 * outcome as a generic server fault rather than a payment failure
 * they can recover from by re-signing.
 */
@ControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class X402SettlementAdvice implements ResponseBodyAdvice<Object> {

    /** v2 transport — preferred server-to-client settlement header. */
    static final String PAYMENT_RESPONSE_HEADER = "PAYMENT-RESPONSE";
    /** v1 compat — emitted alongside the v2 header until 0.3.x SDK adoption is observable. */
    static final String LEGACY_X_PAYMENT_RESPONSE_HEADER = "X-PAYMENT-RESPONSE";

    private final FacilitatorClient facilitatorClient;
    private final PaymentLogRepository paymentLogRepository;
    private final PaymentStore paymentStore;
    private final ObjectMapper objectMapper;
    private final PaymentNotifier paymentNotifier;

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.hasMethodAnnotation(X402Paywall.class);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest servletReq)
                || !(response instanceof ServletServerHttpResponse servletResp)) {
            return body;
        }

        int status = servletResp.getServletResponse().getStatus();
        if (status < 200 || status >= 300) {
            return body;  // Non-2xx — interceptor's afterCompletion releases the signature.
        }

        HttpServletRequest httpReq = servletReq.getServletRequest();
        Object attr = httpReq.getAttribute(X402PaywallInterceptor.REQUEST_ATTR_VERIFIED);
        if (!(attr instanceof VerifiedPayment verified)) {
            return body;
        }

        FacilitatorSettleResponse settle;
        try {
            settle = facilitatorClient.settle(new FacilitatorSettleRequest(
                    X402PaywallInterceptor.X402_VERSION,
                    verified.payload(),
                    verified.requirement()));
        } catch (Exception e) {
            // Drop the stack trace from this log line — it can be quite
            // long and risks accidentally leaking the request URL into
            // operational logs that are not separately access-controlled.
            // Operators that need stack traces during an outage can
            // re-run with TRACE on this logger.
            log.error("x402 settle exception for endpoint={}: {}",
                    verified.endpoint(), e.getMessage());
            FacilitatorSettleResponse synthesised = new FacilitatorSettleResponse(
                    false, "settle_unavailable", "",
                    verified.requirement().network(), verified.payer());
            return failClosed(response, servletReq, servletResp, synthesised, verified);
        }

        if (!settle.success()) {
            // Strip CR/LF before logging — the `errorReason` is
            // facilitator-supplied so a compromised or hostile
            // facilitator could otherwise inject forged log lines.
            String reason = X402PaywallInterceptor.sanitiseLogValue(settle.errorReason());
            log.error("x402 settle rejected: reason={} endpoint={}",
                    reason, verified.endpoint());
            return failClosed(response, servletReq, servletResp, settle, verified);
        }

        attachSettlementHeader(response, settle);
        persistPaymentLog(verified, settle);
        // Paid 200 responses are per-payer (different signatures may
        // produce the same body, but a cache that ignores headers
        // could serve A's response to B). Mark them never-cacheable
        // and Vary on the payment header so any cache that still
        // wants to honour cacheability keys correctly.
        response.getHeaders().set("Cache-Control", "no-store");
        response.getHeaders().add("Vary", X402PaywallInterceptor.PAYMENT_SIGNATURE_HEADER);
        log.info("x402 settled: endpoint={} payer={} amount={} tx={}",
                verified.endpoint(), verified.payer(),
                verified.requirement().amount(), settle.transaction());

        return body;
    }

    /**
     * Rewrite the response to the x402 v2 settle-failure shape:
     * HTTP 402 with the failure {@link FacilitatorSettleResponse}
     * encoded into the {@code PAYMENT-RESPONSE} header (and the
     * legacy {@code X-PAYMENT-RESPONSE} alias) and an empty JSON body.
     *
     * <p>Releases the Redis signature lock immediately. The on-chain
     * EIP-3009 nonce inside the signed authorisation is the source of
     * truth for replay protection — if {@code /settle} failed (for
     * any reason: facilitator transient outage, insufficient funds,
     * network mismatch), the on-chain nonce was NOT consumed, so the
     * same signed authorisation is still cryptographically valid.
     * Holding the Redis lock would prevent a client from recovering
     * via the cleanest path (retry with the same signature once the
     * facilitator is healthy). Releasing here is also defensive
     * against a bypass via the {@code afterCompletion} chain — the
     * lock release is now explicit at the failure point rather than
     * implicit on a 402 status read in
     * {@link X402PaywallInterceptor#afterCompletion}.
     */
    private Object failClosed(ServerHttpResponse response,
                              ServletServerHttpRequest servletReq,
                              ServletServerHttpResponse servletResp,
                              FacilitatorSettleResponse failure,
                              VerifiedPayment verified) {
        paymentStore.release(verified.signatureHash());
        // Mark the request so X402PaywallInterceptor.afterCompletion
        // knows the lock has already been released — keeps the
        // release responsibility unambiguous (otherwise both the
        // advice and the interceptor would re-release on every
        // settlement failure, which is idempotent but obscures intent).
        servletReq.getServletRequest().setAttribute(
                X402PaywallInterceptor.REQUEST_ATTR_LOCK_RELEASED, Boolean.TRUE);
        attachSettlementHeader(response, failure);
        servletResp.getServletResponse().setStatus(HttpStatus.PAYMENT_REQUIRED.value());
        servletResp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // Per-request settlement-failure response — never cache.
        servletResp.getHeaders().set("Cache-Control", "no-store");
        // Empty body per x402 v2 transport spec — all settlement info
        // travels in the PAYMENT-RESPONSE header.
        return new LinkedHashMap<String, Object>();
    }

    private void attachSettlementHeader(ServerHttpResponse response, FacilitatorSettleResponse settle) {
        try {
            byte[] proofBytes = objectMapper.writeValueAsBytes(settle);
            String proof = Base64.getEncoder().encodeToString(proofBytes);
            response.getHeaders().add(PAYMENT_RESPONSE_HEADER, proof);
            response.getHeaders().add(LEGACY_X_PAYMENT_RESPONSE_HEADER, proof);
        } catch (Exception e) {
            log.error("Failed to encode PAYMENT-RESPONSE header: {}", e.getMessage());
        }
    }

    private void persistPaymentLog(VerifiedPayment verified, FacilitatorSettleResponse settle) {
        String rcptNo = X402PaywallInterceptor.extractRcptNo(verified.endpoint());
        BigDecimal amountHuman = new BigDecimal(verified.requirement().amount())
                .divide(X402PaywallInterceptor.USDC_ATOMIC, 6, RoundingMode.HALF_UP);
        // Defence: the facilitator returned success, but if `transaction`
        // is null the on-chain hash is unrecoverable from this row alone.
        // Log loudly so an operator can reconcile against the CDP
        // facilitator's own ledger before persisting the partial row.
        if (settle.transaction() == null || settle.transaction().isBlank()) {
            log.error("x402 settle returned success but no transaction hash — "
                    + "manual reconciliation required: payer={} amount={} sigHash={} "
                    + "endpoint={}. Will persist payment_log with null tx.",
                    verified.payer(), amountHuman,
                    shortHash(verified.signatureHash()), verified.endpoint());
        }
        boolean reconciliationFailure = false;
        try {
            paymentLogRepository.save(new PaymentLog(
                    rcptNo,
                    verified.endpoint(),
                    amountHuman,
                    verified.payer(),
                    verified.requirement().network(),
                    settle.transaction(),
                    verified.signatureHash()
            ));
        } catch (DataIntegrityViolationException violation) {
            // signature_hash has a UNIQUE constraint (V4 migration:
            // uq_payment_log_sig). A genuine duplicate hits this when
            // two settlements landed for the same signature — which
            // the SETNX replay guard plus our explicit-release-on-
            // failure pattern should normally prevent, but a tight
            // race after a transient facilitator outage could in
            // theory get here. The first row is already on disk so
            // the merchant ledger is intact; swallowing the duplicate
            // keeps Spring from turning what is effectively idempotent
            // success into a 500 with the response body half-flushed.
            //
            // BUT — DataIntegrityViolationException is also thrown for
            // schema mismatches (column too small / SQL state 22001),
            // NOT NULL violations (23502), CHECK violations (23514),
            // and FK violations (23503). Round-7 introduced a
            // "nonce:" + 0x + 64-hex replayKey shape (72 chars)
            // without widening the column (still VARCHAR(64)). Every
            // paid mainnet call after that change silently 22001'd,
            // hit this catch, and was dropped from payment_log because
            // we used to treat every integrity exception as "idempotent
            // duplicate". V11 widens the column to 96, and below we
            // inspect the JDBC SQLState so future repeats of the same
            // failure mode are LOUD instead of silent.
            //
            // Note: Hibernate's JPA dialect surfaces UNIQUE violations
            // as the parent DataIntegrityViolationException, NOT as
            // Spring's DuplicateKeyException subclass. Subclass-based
            // catch ordering does not help here — only inspecting the
            // underlying SQLState distinguishes the cases reliably.
            if (isUniqueConstraintViolation(violation)) {
                log.warn("payment_log duplicate row swallowed: sigHash={} tx={} (idempotent)",
                        shortHash(verified.signatureHash()), settle.transaction());
            } else {
                // Schema is out of sync with what the code is trying
                // to write (or a non-null column came in null, or a
                // CHECK failed). The on-chain settlement already
                // happened (funds moved), so the response body still
                // flushes. Treat it exactly the same as the DB-
                // unreachable branch below: log every reconciliation
                // field, flag PaymentNotifier, surface to operator.
                log.error("payment_log integrity violation (NOT duplicate) — "
                        + "settlement landed on-chain but row was rejected: "
                        + "payer={} amount={} network={} tx={} sigHash={} endpoint={} reason={}",
                        verified.payer(), amountHuman, verified.requirement().network(),
                        settle.transaction(), shortHash(verified.signatureHash()),
                        verified.endpoint(),
                        X402PaywallInterceptor.sanitiseLogValue(violation.getMessage()));
                reconciliationFailure = true;
            }
        } catch (org.springframework.dao.DataAccessException dbDown) {
            // Postgres is unreachable / over capacity / mid-failover.
            // The on-chain settlement already happened — funds moved.
            // Log everything an operator needs to reconcile manually
            // (payer wallet, amount, network, tx hash, signature hash)
            // and let the response body still flush to the client. The
            // alternative is to throw, which would surface as a 500 on
            // a request that already paid — strictly worse since the
            // client also won't get the data they paid for.
            log.error("payment_log persist FAILED but settlement landed on-chain: "
                    + "payer={} amount={} network={} tx={} sigHash={} endpoint={} reason={}",
                    verified.payer(), amountHuman, verified.requirement().network(),
                    settle.transaction(), shortHash(verified.signatureHash()),
                    verified.endpoint(),
                    X402PaywallInterceptor.sanitiseLogValue(dbDown.getMessage()));
            reconciliationFailure = true;
        }
        // Notify operator (Slack / Discord webhook). Best-effort,
        // never blocks the request thread, never throws. Driven by
        // X402_NOTIFY_WEBHOOK_URL — empty/unset disables.
        paymentNotifier.notifySettlement(verified, settle, amountHuman, reconciliationFailure);
    }

    private static String shortHash(String hash) {
        return hash != null && hash.length() >= 8 ? hash.substring(0, 8) + "…" : String.valueOf(hash);
    }

    /**
     * Walks the cause chain of a {@link DataIntegrityViolationException}
     * to find the underlying JDBC {@link java.sql.SQLException} and
     * checks whether its SQLState is {@code 23505} (Postgres'
     * {@code unique_violation}).
     *
     * <p>Why not just catch a more specific exception? Hibernate's JPA
     * dialect translates UNIQUE constraint failures into the parent
     * {@code DataIntegrityViolationException}, not into Spring's
     * {@code DuplicateKeyException} subclass — that subclass only
     * fires when {@code JdbcTemplate} is on the path. We use JPA, so
     * the only way to differentiate UNIQUE from string-too-long /
     * NOT NULL / CHECK / FK violations (all of which translate to the
     * same parent class) is to inspect the JDBC SQLState directly.
     *
     * <p>Package-private for unit tests.
     */
    static boolean isUniqueConstraintViolation(DataIntegrityViolationException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sqlEx) {
                // Postgres SQLState "23505" = unique_violation per
                // SQL/Foundation. The constraint name is also in
                // sqlEx.getMessage() and the JPA layer's wrapper, so
                // a future check could tighten to specifically
                // uq_payment_log_sig — but any UNIQUE on this row is
                // safe to treat as idempotent given the table's only
                // such constraint is on signature_hash.
                return "23505".equals(sqlEx.getSQLState());
            }
            cause = cause.getCause();
        }
        return false;
    }
}
