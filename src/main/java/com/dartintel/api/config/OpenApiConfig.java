package com.dartintel.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
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
 * <p>The {@code x402Payment} security scheme documents that paid endpoints
 * expect a base64-encoded signed payload in the {@code PAYMENT-SIGNATURE}
 * header (v2 transport spec). It is intentionally declared as a
 * {@link SecurityScheme.Type#APIKEY} scheme because OpenAPI has no native
 * notion of an EIP-712-signed wallet authorisation; calling it "apiKey in
 * header" lets Swagger UI surface the field while the narrative description
 * points readers to the x402 spec for the real shape. A second
 * {@code x402PaymentLegacy} scheme advertises the {@code X-PAYMENT} header
 * accepted for backward compatibility with 0.2.x SDK / MCP releases.
 */
@Configuration
public class OpenApiConfig {

    private static final String X402_SCHEME = "x402Payment";
    private static final String X402_LEGACY_SCHEME = "x402PaymentLegacy";

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
                        new Server().url("http://localhost:8080").description("Local development")))
                .components(new Components()
                        .addSecuritySchemes(X402_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("PAYMENT-SIGNATURE")
                                .description(
                                        """
                                        Base64-encoded signed x402 payment payload (v2 transport
                                        spec). When this header is absent the server replies
                                        with HTTP 402: the `PAYMENT-REQUIRED` response header
                                        carries the base64-encoded `PaymentRequired` payload,
                                        and a JSON-encoded copy of the same payload is in the
                                        body for v1-era clients. Sign an EIP-3009
                                        TransferWithAuthorization for the declared amount and
                                        re-send the same request with this header set.

                                        On a successful 2xx response, the server attaches a
                                        `PAYMENT-RESPONSE` header carrying the base64-encoded
                                        settlement proof (transaction hash, network, payer).

                                        See https://github.com/coinbase/x402/blob/main/specs/transports-v2/http.md
                                        for the canonical spec and
                                        https://github.com/OldTemple91/korea-filings-api for
                                        the reference Python SDK.
                                        """
                                ))
                        .addSecuritySchemes(X402_LEGACY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-PAYMENT")
                                .description(
                                        """
                                        Legacy v1 transport header. Accepted for backward
                                        compatibility with `koreafilings` 0.2.x SDK and
                                        `koreafilings-mcp` 0.2.x clients that send the v1
                                        header name. New integrations should use
                                        `PAYMENT-SIGNATURE` instead. Either header is
                                        acceptable; do not send both.
                                        """
                                )))
                .addSecurityItem(new SecurityRequirement().addList(X402_SCHEME));
    }
}
