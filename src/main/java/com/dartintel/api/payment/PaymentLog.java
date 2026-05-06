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

    @Column(name = "endpoint", length = 200, nullable = false, updatable = false)
    private String endpoint;

    @Column(name = "amount_usdc", nullable = false, updatable = false, precision = 12, scale = 6)
    private BigDecimal amountUsdc;

    @Column(name = "payer_address", length = 42, nullable = false, updatable = false)
    private String payerAddress;

    @Column(name = "network", length = 40, nullable = false, updatable = false)
    private String network;

    @Column(name = "facilitator_tx_id", length = 80, updatable = false)
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
