# DART Intelligence API

AI-ready English summaries of Korean corporate disclosures (DART filings), delivered via HTTP, paid per call in USDC via the [x402 protocol](https://x402.org).

Built for AI agents, quant funds, and investment research platforms that need programmatic access to Korean market events without reading Korean PDFs.

## Status

🚧 Pre-development. Starting from this skeleton.

## Quick Start (Development)

```bash
cp .env.example .env
# Fill in DART_API_KEY, GEMINI_API_KEY at minimum

docker compose up -d postgres redis
./gradlew bootRun
```

## Documentation

- [`CLAUDE.md`](CLAUDE.md) — project context for Claude Code and future contributors
- [`docs/PRD.md`](docs/PRD.md) — product requirements
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — system design
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — six-week plan to launch
- [`docs/INITIAL_PROMPT.md`](docs/INITIAL_PROMPT.md) — first-session prompt for Claude Code

## Stack

Java 21, Spring Boot 3.x, PostgreSQL 16, Redis 7, Docker Compose, Cloudflare Tunnel. Primary LLM: Gemini 2.5 Flash-Lite. Payment rail: USDC on Base via x402.

## License

TBD.
