# syntax=docker/dockerfile:1.7

# ─────────── Build stage: install the MCP server from PyPI ───────────
# Using the published koreafilings-mcp wheel (and its koreafilings SDK
# dependency) keeps the image identical to what `pip install
# koreafilings-mcp` produces on a developer machine — Glama's smoke
# tests therefore exercise the exact same code path end users run.
FROM python:3.11-slim AS builder

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /install
RUN pip install --target=/install koreafilings-mcp

# ─────────── Runtime: minimal slim image, unprivileged user ───────────
FROM python:3.11-slim

# Run the server as a non-root user. The MCP server only needs outbound
# HTTPS to api.koreafilings.com and stdio for the JSON-RPC channel; no
# write access to the filesystem beyond the Python install dir.
RUN groupadd --system --gid 10001 mcp \
 && useradd  --system --uid 10001 --gid mcp --home-dir /app --shell /sbin/nologin mcp

WORKDIR /app

# Copy the installed package tree from the builder stage and put its
# console scripts on PATH.
COPY --from=builder /install /usr/local/lib/python3.11/site-packages
COPY --from=builder /install/bin/koreafilings-mcp /usr/local/bin/koreafilings-mcp

USER mcp

# Required env vars the MCP client must supply (Glama's admin UI maps
# these to per-deployment secrets):
#   KOREAFILINGS_PRIVATE_KEY  — 0x-prefixed 32-byte hex; signs x402
#                               payments locally. Never logged.
#   KOREAFILINGS_NETWORK      — "base" (mainnet) or "base-sepolia".
#                               Defaults to base-sepolia inside the SDK.
#   KOREAFILINGS_BASE_URL     — override the API host. Defaults to
#                               https://api.koreafilings.com.

# The server speaks MCP over stdio. Glama's deployment harness wires
# the container's stdin/stdout to the MCP client's pipes, so no port
# is exposed and no HEALTHCHECK is required — a successful
# `tools/list` JSON-RPC roundtrip is the de facto health probe.
ENTRYPOINT ["koreafilings-mcp"]
