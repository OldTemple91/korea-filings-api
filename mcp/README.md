# koreafilings-mcp

MCP server that turns [koreafilings.com](https://koreafilings.com) into a
callable tool for Claude Desktop, Cursor, Continue, and any other MCP client.

Ask your agent things like *"Summarise DART filing 20260424900874"* and it
will pay 0.005 USDC on Base via x402, fetch the AI summary, and hand it back
as structured data.

## Tools

| tool                     | payment        | what it does |
|--------------------------|----------------|--------------|
| `get_pricing`            | free           | Current per-endpoint prices, wallet address, network, USDC contract. |
| `get_disclosure_summary` | 0.005 USDC     | English summary, importance score (1–10), event type, ticker tags for one DART receipt number. Includes the on-chain settlement tx hash. |

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
           "KOREAFILINGS_PRIVATE_KEY": "0x_your_test_wallet_key",
           "KOREAFILINGS_NETWORK": "base-sepolia"
         }
       }
     }
   }
   ```

3. Restart Claude Desktop. A koreafilings tool should now appear in the 🔧
   menu.

4. Ask: *"Summarise DART disclosure 20260424900874"*. Claude calls
   `get_disclosure_summary`, the MCP server signs an EIP-3009 authorization
   with your key, pays 0.005 USDC on Base Sepolia, and returns the English
   summary plus a BaseScan tx link.

## Configuration

| env var | required | default | notes |
|---|---|---|---|
| `KOREAFILINGS_PRIVATE_KEY` | for paid tools | — | 0x-prefixed 32-byte hex; signs the x402 payment locally. The key never leaves the MCP server process. |
| `KOREAFILINGS_NETWORK` | no | `base-sepolia` | `base-sepolia` or `base`. Must match the server's advertised 402 response or the SDK aborts before signing. |
| `KOREAFILINGS_BASE_URL` | no | `https://api.koreafilings.com` | Override for self-hosted deployments. |

`get_pricing` works without a private key — use it to confirm the network
and wallet before funding the payer.

## Local development

```bash
git clone https://github.com/OldTemple91/korea-filings-api.git
cd korea-filings-api
uv venv mcp/.venv
source mcp/.venv/bin/activate
uv pip install -e mcp/
KOREAFILINGS_PRIVATE_KEY=0x... koreafilings-mcp  # stdio; pipe to an MCP client
```

Inspect interactively with the official MCP Inspector:

```bash
npx @modelcontextprotocol/inspector uv run koreafilings-mcp
```

## Security notes

- The MCP server is trusted with a wallet private key — only ever use a
  burner wallet funded with the test USDC you intend to spend.
- On Base Sepolia all amounts are testnet value. On `base` mainnet each
  tool call moves real USDC.
- The SDK signs locally; the key is not transmitted to koreafilings.com
  or the facilitator. Only the signed authorization goes on the wire.

## Links

- Landing: <https://koreafilings.com>
- Repo: <https://github.com/OldTemple91/korea-filings-api>
- Python SDK: <https://pypi.org/project/koreafilings/>

MIT-licensed.
