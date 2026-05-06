package com.dartintel.api.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment_log")
public class PaymentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "rcpt_no_accessed", length = 14, updatable = false)
    private String rcptNoAccessed;

    // Widened from 200 in V12. Today's longest endpoint is under 60
    // chars, but a structured-filter or multi-constraint query
    // endpoint planned for v1.x could exceed 200 in the query-string
    // tail and silently reconcile-fail the same way V11 was added
    // to prevent. 500 is generous defence in depth.
    @Column(name = "endpoint", length = 500, nullable = false, updatable = false)
    private String endpoint;

    @Column(name = "amount_usdc", nullable = false, updatable = false, precision = 12, scale = 6)
    private BigDecimal amountUsdc;

    @Column(name = "payer_address", length = 42, nullable = false, updatable = false)
    private String payerAddress;

    @Column(name = "network", length = 40, nullable = false, updatable = false)
    private String network;

    // Widened from 80 in V12. The CDP facilitator currently returns a
    // 66-character Ethereum tx hash (0x + 64 hex), but x402 v2 leaves
    // the transaction reference format up to the facilitator and any
    // future CAIP-prefixed shape would silently 22001 against the old
    // cap. 200 decouples the column from the wire format.
    @Column(name = "facilitator_tx_id", length = 200, updatable = false)
    private String facilitatorTxId;

    // Widened from 64 in V11 to fit the "nonce:" + 0x + 64-hex
    // replay-key format introduced in round-7. Hibernate uses this
    // length when ddl-auto is `validate` (it is, in production), so
    // the JPA annotation must match the column TYPE in lock-step
    // with the V11 migration or boot fails with a schema mismatch.
    @Column(name = "signature_hash", length = 96, nullable = false, updatable = false)
    private String signatureHash;

    @CreationTimestamp
    @Column(name = "settled_at", nullable = false, updatable = false)
    private Instant settledAt;

    protected PaymentLog() {
    }

    public PaymentLog(
            String rcptNoAccessed,
            String endpoint,
            BigDecimal amountUsdc,
            String payerAddress,
            String network,
            String facilitatorTxId,
            String signatureHash
    ) {
        this.rcptNoAccessed = rcptNoAccessed;
        this.endpoint = endpoint;
        this.amountUsdc = amountUsdc;
        this.payerAddress = payerAddress;
        this.network = network;
        this.facilitatorTxId = facilitatorTxId;
        this.signatureHash = signatureHash;
    }

    public Long getId() {
        return id;
    }

    public String getRcptNoAccessed() {
        return rcptNoAccessed;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public BigDecimal getAmountUsdc() {
        return amountUsdc;
    }

    public String getPayerAddress() {
        return payerAddress;
    }

    public String getNetwork() {
        return network;
    }

    public String getFacilitatorTxId() {
        return facilitatorTxId;
    }

    public String getSignatureHash() {
        return signatureHash;
    }

    public Instant getSettledAt() {
        return settledAt;
    }
}
