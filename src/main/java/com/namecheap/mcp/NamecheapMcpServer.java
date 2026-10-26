package com.namecheap.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class NamecheapMcpServer {

    private static final Logger log = LoggerFactory.getLogger(NamecheapMcpServer.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String SERVER_NAME = "namecheap";
    private static final String SERVER_VERSION = "1.0.0";

    public static void main(String[] args) {
        try {
            if (args.length > 0 && "--init".equals(args[0])) {
                String claudeBinary = null;
                for (int i = 1; i < args.length - 1; i++) {
                    if ("--claude-binary".equals(args[i])) {
                        claudeBinary = args[i + 1];
                        break;
                    }
                }
                NamecheapAuth.init(claudeBinary);
                return;
            }

            TransportConfig transport = TransportConfig.fromArgs(args);
            var config = NamecheapAuth.getConfig();
            var rateLimiter = new RateLimiter();
            var client = new NamecheapClient(config, rateLimiter);
            List<SyncToolSpecification> tools = tools(client);

            if (transport.http()) {
                startHttpServer(tools, transport);
            } else {
                startServer(tools);
            }
        } catch (Exception e) {
            log.error("Failed to start Namecheap MCP server", e);
            System.exit(1);
        }
    }

    // one list keeps the stdio and http paths from drifting apart
    static List<SyncToolSpecification> tools(NamecheapClient client) {
        return List.of(
                NamecheapTools.listDomains(client),
                NamecheapTools.getDomainInfo(client),
                NamecheapTools.getDnsHosts(client),
                NamecheapTools.getNameservers(client),
                NamecheapTools.setDnsHosts(client),
                NamecheapTools.addDnsRecord(client),
                NamecheapTools.removeDnsRecord(client),
                NamecheapTools.setNameservers(client));
    }

    private static void startHttpServer(List<SyncToolSpecification> tools, TransportConfig transport)
            throws Exception {
        HttpTransportServer server = HttpTransportServer.start(SERVER_NAME, SERVER_VERSION, tools,
                transport.bindHost(), transport.port(), transport.readToken());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Namecheap MCP server");
            try {
                server.stop();
            } catch (Exception e) {
                log.warn("Error during shutdown: {}", e.getMessage());
            }
        }));
        server.join();
    }

    private static void startServer(List<SyncToolSpecification> tools) {
        var transport = new StdioServerTransportProvider(
                new JacksonMcpJsonMapper(OBJECT_MAPPER));

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .tools(tools)
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Namecheap MCP server");
            server.close();
        }));

        log.info("Namecheap MCP server started");
    }
}
