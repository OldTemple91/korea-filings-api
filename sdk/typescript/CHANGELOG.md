# Changelog

All notable changes to the `koreafilings` TypeScript SDK are
documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this package adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- README adds a real Base mainnet body-aware summary example
  ([Samsung Electronics 2026-Q1 dividend](https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260430800106))
  alongside the existing usage snippet, plus a short paragraph on
  the round-11 (server-side) lazy body-fetch behaviour. No SDK code
  changed — pure docs. **Publish a `0.1.3` patch to surface this
  README on the npm package page**, since npm only re-pulls the
  README on a new version publish; without the bump, agent builders
  browsing npm still see the 0.1.2 README.

## [0.1.2] — 2026-05-06

### Added

- **`KNOWN_DOMAINS` allowlist** in `payment.ts`. The SDK now hard-
  codes the canonical EIP-712 domain values (`name`, `version`,
  `verifyingContract`) for known chains (Base mainnet, Base Sepolia)
  and refuses to sign if a server's 402 challenge advertises a
  non-canonical USDC contract. Throws `PaymentError('asset_mismatch', { expected, observed, chainId, recommendation: 'refuse_server' })`.
  Defends against a malicious or MITM-injected server substituting
  a different contract (which would burn the EIP-3009 nonce or
  authorise a transfer against an unrelated contract). Unknown
  chains still fall through with a `console.warn` for forward compat.
- **Structured `PaymentError.detail`** for `asset_mismatch` and
  `network_mismatch`. Previously a human-readable string; now an
  object with `expected` / `observed` / `recommendation` /
  `rationale` so an LLM agent can branch on `e.detail.recommendation`
  without parsing the human message.
- **`client.lastSettlementError`** — diagnostic field for the case
  where a paid call returned 200 but the `PAYMENT-RESPONSE` header
  was missing, malformed, or oversized. Returns `null` on a clean
  parse, `'header_absent'` or `'malformed_or_oversize'` on a paid
  200 the SDK refused to surface. Disambiguates the three previously-
  identical null-`lastSettlement` cases (free-tier / header-absent /
  malformed-oversize). The SDK also `console.warn`s in those cases.
- **Client-side input validation** for `getSummary` (rcptNo regex
  `^\d{14}$`) and `getRecentFilings` (ticker regex `^[0-9A-Z]{6,7}$`)
  — throws `ConfigurationError` BEFORE the unpaid GET, so a malformed
  input does not consume one of the wallet's 60-second EIP-3009
  nonce slots. Patterns mirror the server's `@Pattern` annotations
  in `DisclosuresController.java`.
- **`SettlementProof` shape guard** in `decodeSettlementHeader`.
  Rejects payloads larger than 16 KB and payloads that aren't a
  JSON object with a boolean `success` field. Defends against a
  server stuffing a multi-megabyte `errorReason` into the header.

### Changed

- **CAIP-2 network parsing tightened** — was `Number.isFinite(Number(network.split(':')[1]))`,
  now `network.match(/^eip155:(\d+)$/)`. Malformed shapes
  (`eip155`, `eip155:8453:extra`, `solana:mainnet`) now reject
  deterministically before signing instead of relying on accidental
  robustness.
- **`baseUrl` trailing-slash strip** widened from `/\/$/` to `/\/+$/`
  — catches `https://api.koreafilings.com//` which would otherwise
  produce `//v1/...` URLs.

### Tests

- viem 2.21.0 typed-data canary: a deterministic `(privateKey,
  requirement, authorization)` triple is signed and the output is
  asserted byte-for-byte against the value captured on viem 2.21.0.
  Any silent minor-version bump that changes the EIP-712 typed-data
  encoding will fail this test before a broken SDK can ship.
- KNOWN_DOMAINS positive lock-down: signing with the canonical
  Base mainnet `(USD Coin / "2" / 0x833589fCD…)` triple round-trips
  through `recoverTypedDataAddress`.
- `decodeSettlementHeader` boundary tests for the 16 KB cap and
  shape-guard rejection of non-object / missing-success-boolean
  payloads.
- Client-side validation: rcptNo / ticker rejected before any fetch
  call goes out (asserted via `expect(fetchSpy).not.toHaveBeenCalled()`).

## [0.1.1] — 2026-05-06

### Added

- **`viem` pinned to exact `2.21.0`** (no caret). For an SDK whose
  only dependency touches the EIP-712 typed-data hashing path, a
  silent minor-version bump in viem could change the encoding and
  produce signatures that look valid but verify against a different
  domain. Bumping is now a manual review step.
- Initial round of input validation and CAIP-2 parsing (made stricter
  in 0.1.2).

### Fixed

- N/A — first patch release after 0.1.0.

## [0.1.0] — 2026-05-06

### Added

- Initial public release on npm.
- `KoreaFilings` client class with the same surface as the Python
  SDK (idiomatic camelCase per language convention).
- Methods: `findCompany`, `getCompany`, `listRecentFilings`,
  `getPricing`, `getSummary` (paid 0.005 USDC), `getRecentFilings`
  (paid 0.005 × limit USDC).
- Full x402 v2 flow: 402 → EIP-3009 `TransferWithAuthorization`
  signing via viem → retry with `PAYMENT-SIGNATURE` header → 200 +
  `PAYMENT-RESPONSE` proof. Legacy `X-PAYMENT-RESPONSE` alias
  accepted.
- Network aliases: `base` / `base-mainnet` / `base-sepolia`.
- Error classes: `KoreaFilingsError`, `ApiError`, `PaymentError`,
  `ConfigurationError`. Network-mismatch guard throws
  `PaymentError` BEFORE signing if the server advertises a chain
  different from the client's configured network.
- ESM + CJS dual publish via tsup. Native fetch (Node 18+ /
  browsers / Cloudflare Workers / Deno) — no `node-fetch`
  dependency.
- 23 vitest unit tests covering signature recovery, network
  guards, the full mock-fetch 402 → sign → retry flow, and the
  legacy `X-PAYMENT-RESPONSE` alias.
