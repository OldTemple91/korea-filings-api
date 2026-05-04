"""koreafilings-mcp — MCP server wrapping the koreafilings paid API.

The server is launched via the ``koreafilings-mcp`` console script
registered in pyproject.toml. Claude Desktop (and any MCP client) can
spawn it over stdio with a single config entry; the server then
exposes the koreafilings SDK's paid endpoints as tools the model can
call.
"""

__version__ = "0.3.0"
