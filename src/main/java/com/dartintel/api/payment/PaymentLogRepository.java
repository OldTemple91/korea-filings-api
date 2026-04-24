package com.dartintel.api.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentLogRepository extends JpaRepository<PaymentLog, Long> {

    boolean existsBySignatureHash(String signatureHash);
}
