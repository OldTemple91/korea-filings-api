# koreafilings-mcp

MCP server that turns [koreafilings.com](https://koreafilings.com) into a
callable toolset for Claude Desktop, Cursor, Continue, and any other MCP
client. Built on top of the [`koreafilings`](https://pypi.org/project/koreafilings/)
Python SDK; the underlying HTTP + x402 + EIP-3009 flow is delegated entirely
to the SDK so this package is just the protocol bridge.

Once installed, your agent can resolve a Korean company by name, browse
the market-wide disclosure feed for free, and pay per call to fetch
AI-generated English summaries — all without leaving the chat.

## Tools

| tool                     | payment           | what it does |
|--------------------------|-------------------|--------------|
| `get_pricing`            | free              | Live pricing descriptor — wallet, network, USDC contract, per-endpoint price. |
| `find_company`           | free              | Trigram fuzzy search of 3,961 KRX-listed companies by Korean name, English name, or ticker. Returns `ticker`, `corp_code`, `name_kr`, `name_en`, `market`. |
| `list_recent_filings`    | free              | Market-wide recent DART filings (metadata only — title, date, filer, ticker). Use to scan before paying for summaries. |
| `get_recent_filings`     | **0.005 × limit USDC** | Batch AI summaries for one ticker. Price is declared dynamically in the 402 (`limit=5` → 0.025 USDC). Includes the on-chain settlement tx hash. |
| `get_disclosure_summary` | **0.005 USDC**    | AI summary for a single DART receipt number when the agent already has one. |

The natural agent flow is `find_company` → `get_recent_filings` (one
free call to resolve a name to a ticker, one paid call to fetch summaries
for that ticker). `get_disclosure_summary` is the direct path when the
agent already has a 14-digit receipt number from somewhere else.

## Quickstart — Claude Desktop

1. Install (requires [uv](https://docs.astral.sh/uv/)):

   ```bash
   uv tool install koreafilings-mcp
   ```

2. Open Claude Desktop → **Settings → Developer → Edit Config**, and add:

   ```json
   {
     "mcpServers": {
       "koreafilings": {
         "command": "uv",
         "args": ["tool", "run", "koreafilings-mcp"],
         "env": {
           "KOREAFILINGS_PRIVATE_KEY": "0x_your_burner_wallet_key",
           "KOREAFILINGS_NETWORK": "base"
         }
       }
     }
   }
   ```

   Use a fresh burner wallet funded only with the USDC you intend to
   spend — see "Security notes" below.

3. Restart Claude Desktop. Five koreafilings tools should now appear in
   the 🔧 menu.

4. Ask: *"What did Samsung Electronics file at DART recently?"* Claude
   calls `find_company("Samsung Electronics")`, picks `005930` from the
   matches, then calls `get_recent_filings("005930", limit=5)`. The MCP
   server signs an EIP-3009 authorization with your key, pays
   0.025 USDC on Base, and returns five English summaries with importance
   scores plus a BaseScan tx link.

## Quickstart — Cursor / Continue

The MCP server speaks stdio so any client supporting the MCP protocol
will pick up the same five tools. Point your client at:

```bash
uv tool run koreafilings-mcp
```

with the same two environment variables (`KOREAFILINGS_PRIVATE_KEY`,
`KOREAFILINGS_NETWORK=base`).

## Configuration

| env var | required | default | notes |
|---|---|---|---|
| `KOREAFILINGS_PRIVATE_KEY` | for paid tools | — | 0x-prefixed 32-byte hex; signs the x402 payment locally. The key never leaves the MCP server process. |
| `KOREAFILINGS_NETWORK` | no | `base-sepolia` | `base-sepolia` (testnet) or `base` (mainnet). Must match the server's advertised 402 response or the SDK aborts before signing. |
| `KOREAFILINGS_BASE_URL` | no | `https://api.koreafilings.com` | Override for self-hosted deployments. |

Free tools (`get_pricing`, `find_company`, `list_recent_filings`) work
without a private key — useful to confirm the network and wallet, or to
explore the feed, before funding a payer.

## Local development

```bash
git clone https://github.com/OldTemple91/korea-filings-api.git
cd korea-filings-api
uv venv mcp/.venv
source mcp/.venv/bin/activate
uv pip install -e mcp/
KOREAFILINGS_PRIVATE_KEY=0x... KOREAFILINGS_NETWORK=base koreafilings-mcp
# stdio; pipe to an MCP client
```

Inspect interactively with the official MCP Inspector:

```bash
npx @modelcontextprotocol/inspector uv run koreafilings-mcp
```

## Security notes

- The MCP server is trusted with a wallet private key — only ever use a
  **fresh burner wallet** funded with the USDC you intend to spend.
  Same threat model as any per-call merchant integration.
- On Base Sepolia all amounts are testnet value. On `base` mainnet each
  paid tool call moves real USDC.
- The SDK signs locally; the key is not transmitted to koreafilings.com
  or to the facilitator. Only the signed authorization goes on the wire.
- Each EIP-3009 authorization carries a fresh nonce. The facilitator
  refuses replays.

## Links

- Landing: <https://koreafilings.com>
- Repo: <https://github.com/OldTemple91/korea-filings-api>
- Python SDK: <https://pypi.org/project/koreafilings/>

MIT-licensed.
