package com.dartintel.api;

import java.util.List;

/**
 * Provider-level identity published on every discovery surface —
 * the {@code /.well-known/x402} document and the {@code resource}
 * object of every 402 challenge. The x402 Bazaar extension reads
 * {@code serviceName} / {@code tags} / {@code iconUrl} from the 402
 * {@code resource} object to render and rank the catalog entry, so
 * these live in one place to keep the surfaces consistent.
 */
public final class ServiceIdentity {

    public static final String NAME = "Korea Filings";
    public static final String HOMEPAGE = "https://koreafilings.com";
    public static final String ICON_URL = "https://koreafilings.com/korea-filings-logo.png";
    public static final List<String> TAGS = List.of(
            "korea", "dart", "disclosures", "filings", "kospi", "kosdaq",
            "equities", "finance", "english");

    private ServiceIdentity() {
    }
}
