package com.namecheap.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NamecheapMcpServerTest {

    @Test
    void sanitizedResult_includesSecurityContextAndJson() {
        var record = new LinkedHashMap<String, Object>();
        record.put("name", "www");
        record.put("type", "A");
        record.put("address", "1.2.3.4");

        CallToolResult result = ResultHelper.sanitizedResult(List.of(record));

        assertFalse(result.isError());
        assertEquals(2, result.content().size());

        String securityContext = ((TextContent) result.content().get(0)).text();
        assertTrue(securityContext.contains("UNTRUSTED"));

        String json = ((TextContent) result.content().get(1)).text();
        assertTrue(json.contains("----UNTRUSTED_CONTENT_"));
        assertTrue(json.contains("www"));
        assertFalse(json.contains("----UNTRUSTED_CONTENT_") && json.contains("\"type\" : \"----UNTRUSTED"));
    }

    @Test
    void jsonResult_returnsPlainJson() {
        var data = Map.of("isSuccess", true, "domain", "example.com");
        CallToolResult result = ResultHelper.jsonResult(data);

        assertFalse(result.isError());
        assertEquals(1, result.content().size());

        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("example.com"));
        assertFalse(json.contains("UNTRUSTED"));
    }

    @Test
    void errorResult_setsIsError() {
        CallToolResult result = ResultHelper.errorResult("something went wrong");

        assertTrue(result.isError());
        assertEquals(1, result.content().size());
        assertEquals("something went wrong", ((TextContent) result.content().get(0)).text());
    }

    @Test
    void sanitizedResult_doesNotWrapTrustedFields() {
        var record = new LinkedHashMap<String, Object>();
        record.put("type", "A");
        record.put("ttl", "1800");
        record.put("hostId", "12345");
        record.put("name", "www");

        CallToolResult result = ResultHelper.sanitizedResult(List.of(record));
        String json = ((TextContent) result.content().get(1)).text();

        assertTrue(json.contains("\"type\" : \"A\""));
        assertTrue(json.contains("\"ttl\" : \"1800\""));
        assertTrue(json.contains("\"hostId\" : \"12345\""));
    }
}
