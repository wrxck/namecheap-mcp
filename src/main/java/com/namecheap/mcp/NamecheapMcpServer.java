package com.namecheap.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NamecheapMcpServer {

    private static final Logger log = LoggerFactory.getLogger(NamecheapMcpServer.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        try {
            if (args.length > 0 && "--init".equals(args[0])) {
                NamecheapAuth.init();
                return;
            }

            var config = NamecheapAuth.getConfig();
            var rateLimiter = new RateLimiter();
            var client = new NamecheapClient(config, rateLimiter);

            startServer(client);
        } catch (Exception e) {
            log.error("Failed to start Namecheap MCP server", e);
            System.exit(1);
        }
    }

    private static void startServer(NamecheapClient client) {
        var transport = new StdioServerTransportProvider(
                new JacksonMcpJsonMapper(OBJECT_MAPPER));

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("namecheap", "1.0.0")
                .capabilities(ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .tools(
                        NamecheapTools.listDomains(client),
                        NamecheapTools.getDomainInfo(client),
                        NamecheapTools.getDnsHosts(client),
                        NamecheapTools.getNameservers(client),
                        NamecheapTools.setDnsHosts(client),
                        NamecheapTools.addDnsRecord(client),
                        NamecheapTools.removeDnsRecord(client),
                        NamecheapTools.setNameservers(client))
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Namecheap MCP server");
            server.close();
        }));

        log.info("Namecheap MCP server started");
    }
}
