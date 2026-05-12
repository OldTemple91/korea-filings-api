package com.dartintel.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * springdoc customisation. The auto-generated OpenAPI document is fine for
 * wiring up Swagger UI, but consumers reading the schema outside this app
 * (SDK codegen, Postman collections, registrars like x402scan) expect real
 * metadata: title, licence, contact, servers.
 *
 * <h2>No OpenAPI security scheme is declared for x402 payment</h2>
 *
 * <p>Earlier rounds declared an {@code x402Payment} security scheme of type
 * {@code apiKey in header: PAYMENT-SIGNATURE} (plus a legacy
 * {@code X-PAYMENT} sibling) so Swagger UI could expose an "Authorize" box.
 * That was removed in round-13 once direct testing showed
 * {@code agentcash discover} was labelling every paid endpoint as
 * {@code apiKey + paid} based on those declarations. Agents filtering for
 * "pure paid, no API key required" silently dropped Korea Filings from
 * their candidate set, and the discovery surface mis-described the
 * service: there is no API key, there are no signups, the wallet that
 * signs the {@code PAYMENT-SIGNATURE} header <em>is</em> the identity.
 *
 * <p>The canonical descriptors for the payment requirement now live in
 * three already-public places that catalogs already read:
 *
 * <ul>
 *   <li>The {@code /.well-known/x402} discovery document
 *       ({@link com.dartintel.api.api.WellKnownController}) for x402scan
 *       and AgentCash.</li>
 *   <li>The {@code x-payment-info} OpenAPI extension attached per-operation
 *       by {@link X402OpenApiCustomizer} for tools that want machine-
 *       readable pricing at the operation level.</li>
 *   <li>The HTTP 402 challenge ({@link com.dartintel.api.payment.X402PaywallInterceptor})
 *       which always carries the canonical {@code PaymentRequired} body
 *       at request time.</li>
 * </ul>
 *
 * <p>Swagger UI loses the "Authorize" prompt as a side effect, which is
 * fine because no human can actually paste a valid base64 signed EIP-3009
 * payload into it anyway — x402 calls require an SDK or wallet-signing
 * MCP host.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI(
            @Value("${spring.application.name:korea-filings-api}") String appName,
            @Value("${info.app.version:0.0.1}") String appVersion
    ) {
        return new OpenAPI()
                .info(new Info()
                        .title("koreafilings API")
                        .description(
                                """
                                Commercial API that serves AI-generated English summaries of
                                Korean DART (금융감독원 전자공시) corporate disclosures.

                                Every paid endpoint settles on-chain in USDC via the
                                [x402](https://www.x402.org/) payment protocol. The first
                                call for a disclosure triggers an LLM run; every subsequent
                                call for the same receipt number is served from a shared
                                cache for the same price, so the marginal cost to the
                                operator approaches zero as adoption grows.

                                **No API keys. No signup. No monthly fees.** The wallet
                                that signs the `PAYMENT-SIGNATURE` header *is* the identity.
                                See `/.well-known/x402` for the canonical payment descriptor
                                and `x-payment-info` on each paid operation for per-route
                                pricing metadata.
                                """
                        )
                        .version(appVersion)
                        .contact(new Contact()
                                .name("korea-filings-api")
                                .url("https://github.com/OldTemple91/korea-filings-api")
                                .email("***"))
                        .license(new License()
                                .name("MIT")
                                .url("https://github.com/OldTemple91/korea-filings-api/blob/main/LICENSE")))
                .servers(List.of(
                        new Server().url("https://api.koreafilings.com").description("Production"),
                        new Server().url("http://localhost:8080").description("Local development")));
    }
}
