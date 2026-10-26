# Namecheap MCP Server

A [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server for managing Namecheap domains and DNS records from AI assistants like [Claude Code](https://docs.anthropic.com/en/docs/claude-code). Built in Java 21 using the official [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk).

## Features

| Tool | Description |
|------|-------------|
| `list_domains` | List all domains on the account with expiry dates and status |
| `get_domain_info` | Get detailed info for a domain including DNS details and nameservers |
| `get_dns_hosts` | Get all DNS host records for a domain |
| `get_nameservers` | Get the nameservers configured for a domain |
| `add_dns_record` | Add a single DNS record (preserves existing records) |
| `remove_dns_record` | Remove a matching DNS record by name, type, and optionally address |
| `set_dns_hosts` | Replace **all** DNS records for a domain (use with caution) |
| `set_nameservers` | Set custom nameservers or revert to Namecheap defaults |
| `get_dnssec` | Get the DS records registered for a domain |
| `add_dnssec_record` | Add a DS record at the registrar (needs admin-panel cookies, see below) |
| `remove_dnssec_record` | Remove a DS record by its ID (needs admin-panel cookies, see below) |

Write tools (`add_dns_record`, `remove_dns_record`, `set_dns_hosts`, `set_nameservers`) always return the previous state in their response so changes can be reverted.

Supported record types: `A`, `AAAA`, `CNAME`, `MX`, `MXE`, `TXT`, `URL`, `URL301`, `FRAME`.

`add_dnssec_record` and `remove_dnssec_record` call Namecheap admin-panel endpoints, because the public API cannot change DS records. They read a logged-in session from `~/.namecheap-mcp/ap-cookies.txt`, copied from the browser dev tools. The session expires after about 2 hours.

## Prerequisites

- Java 21+
- Maven 3.6+
- A Namecheap account with [API access enabled](https://ap.www.namecheap.com/settings/tools/apiaccess/)
- Your server's IP whitelisted in Namecheap's API settings

## Setup

### 1. Build

```bash
git clone https://github.com/wrxck/namecheap-mcp.git
cd namecheap-mcp
mvn clean package
```

This produces a single uber-jar at `target/namecheap-mcp-1.0.0.jar`.

### 2. Init

```bash
java -jar target/namecheap-mcp-1.0.0.jar --init
```

This walks you through two steps:

1. **API credentials** — prompts for your Namecheap API user, key, and whitelisted IP. Saved to `~/.namecheap-mcp/config.properties` with `600` permissions.
2. **Claude Code registration** — automatically registers the server with Claude Code via `claude mcp add`.

The init is idempotent. If config already exists, it skips straight to registration. If already registered, it skips that too. Safe to re-run.

If Claude Code isn't on your `PATH`, pass the binary location:

```bash
java -jar target/namecheap-mcp-1.0.0.jar --init --claude-binary ~/.local/bin/claude
```

If auto-registration fails (e.g. CI environment, no Claude installed), it prints the manual command:

```bash
claude mcp add --scope user --transport stdio namecheap -- \
  java -jar /path/to/namecheap-mcp-1.0.0.jar
```

Restart Claude Code after registration. The 11 Namecheap tools will be available immediately.

### Command-line options

| Flag | Description | Default |
|------|-------------|---------|
| `--init` | Run the credential and Claude Code setup, then exit | |
| `--claude-binary` | Path to the `claude` binary for `--init` | `claude` on `PATH` |
| `--transport` | `stdio` (one client) or `http` (one shared server for many clients) | `stdio` |
| `--bind` | Address for `http` | `127.0.0.1` |
| `--port` | Port for `http` | `7403` |
| `--auth-token-file` | File with the bearer token; required for `http` | |

### Shared HTTP server

By default each Claude session starts its own copy of the server. With `--transport http`, one copy serves every session over MCP streamable HTTP at `/mcp`.

```bash
java -Xmx128m -XX:+UseSerialGC -jar target/namecheap-mcp-1.0.0.jar \
  --transport http --port 7403 --auth-token-file /run/credentials/mcp-namecheap.service/token
```

Every request must send `Authorization: Bearer <token>`. The server refuses to start if the token file is readable by every user, or if the token has fewer than 32 characters. Register it in Claude Code with a `headersHelper` that prints the header:

```json
"namecheap": {
  "type": "http",
  "url": "http://127.0.0.1:7403/mcp",
  "headersHelper": "/usr/local/bin/mcp-auth-header namecheap"
}
```

The server still reads its Namecheap credentials from `~/.namecheap-mcp/config.properties` of the user that runs it.

## Usage examples

Once registered, you can ask Claude:

- "List my domains"
- "Show DNS records for example.com"
- "Add a TXT record for `_dmarc.example.com` with value `v=DMARC1; p=reject`"
- "Point `www.example.com` to `1.2.3.4`"
- "Remove the CNAME record for `old.example.com`"
- "What nameservers are set for example.com?"

## Security

DNS data returned by the API passes through an LLM's context window. Domain names are attacker-controllable (e.g. someone could register `IGNORE-ALL-INSTRUCTIONS.com`), so the server applies the same defense-in-depth pattern used by the [Gmail MCP server](https://github.com/wrxck/gmail-mcp):

- **Random content boundaries** — Each read-tool response generates a cryptographically random boundary string (via `SecureRandom`). Untrusted fields (`domainName`, `name`, `address`, `value`, `host`) are wrapped with this boundary so the LLM can distinguish DNS data from system structure. The boundary is unpredictable, so an attacker cannot embed it in a domain name to escape the content region.
- **Security context preamble** — Every read-tool response includes a leading text block explaining the boundary token and instructing the LLM to treat bounded content as data only.
- **XXE prevention** — The XML parser disables DTDs, external general entities, external parameter entities, and external DTD/schema access. This prevents XML External Entity attacks from malicious API responses.
- **Input validation** — Domain SLD/TLD are validated against strict regexes before URL construction. Record types are restricted to a known enum. IP addresses are validated for A/AAAA records. TTL and MX priority ranges are enforced.
- **Rate limiting** — Client-side sliding window limits requests to 20/minute and 700/hour, matching Namecheap's API limits.
- **API key protection** — Config directory uses `rwx------` permissions, config file uses `rw-------`. The API key is never logged.
- **HTTP auth** — In `http` mode, every request needs the bearer token. The compare is constant-time. Bind to `127.0.0.1` only; containers on the host network can reach loopback, and the token is what keeps them out.

Write-tool responses (`set_dns_hosts`, `add_dns_record`, `remove_dns_record`, `set_nameservers`) use plain JSON without boundaries since they contain only system-controlled success/failure data.

## Architecture

```
Claude Code  <--stdio (JSON-RPC)-->  namecheap-mcp.jar  <--HTTPS/XML-->  Namecheap API
                                           |
                                     ~/.namecheap-mcp/
                                       config.properties
```

- **Transport** — stdio by default (Claude Code spawns the server as a subprocess); optional shared streamable HTTP at `/mcp` with `--transport http`
- **Auth** — static API key + IP whitelist (no OAuth, no token refresh)
- **API format** — Namecheap uses XML responses; parsed with a hardened `DocumentBuilderFactory`
- **Logging** — all logs go to stderr (stdout is reserved for MCP JSON-RPC)

## Configuration

Credentials are stored in `~/.namecheap-mcp/` with restricted permissions (`rwx------`):

```
~/.namecheap-mcp/
  config.properties    # API credentials (created by --init)
```

The config file contains:

| Property | Description |
|----------|-------------|
| `apiUser` | Your Namecheap username |
| `apiKey` | Your Namecheap API key |
| `userName` | Usually the same as `apiUser` |
| `clientIp` | Your whitelisted IP address |
| `useSandbox` | `true` to use the sandbox API (default `false`) |

## Development

```bash
# build
mvn clean package

# run tests
mvn test

# run init
java -jar target/namecheap-mcp-1.0.0.jar --init

# run server (blocks on stdio — use with Claude Code or an MCP client)
java -jar target/namecheap-mcp-1.0.0.jar
```

### Project structure

```
src/main/java/com/namecheap/mcp/
  NamecheapMcpServer.java    # Entry point, server setup
  TransportConfig.java       # --transport/--bind/--port/--auth-token-file parsing
  HttpTransportServer.java   # Embedded Jetty, streamable HTTP at /mcp, bearer auth
  NamecheapTools.java        # MCP tool definitions (11 tools)
  NamecheapClient.java       # HTTP client, XML parsing, API calls
  NamecheapAuth.java         # Config loading, --init flow, Claude registration
  ContentSanitizer.java      # Prompt injection mitigations
  RateLimiter.java           # Sliding window rate limiter
  ResultHelper.java          # Response serialisation helpers

src/test/java/com/namecheap/mcp/
  ContentSanitizerTest.java  # Boundary wrapping, field sanitisation
  RateLimiterTest.java       # Rate limit enforcement
  NamecheapClientTest.java   # Input validation, domain parsing
  NamecheapMcpServerTest.java # Response pipeline (sanitised vs plain JSON)
  TransportConfigTest.java   # Flag parsing, token file checks
  HttpTransportServerTest.java # Bearer auth, /mcp routing
```

## License

[MIT](LICENSE)
