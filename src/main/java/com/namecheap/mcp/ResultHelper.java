package com.namecheap.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.util.List;
import java.util.Map;

public final class ResultHelper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ResultHelper() {}

    static CallToolResult sanitizedResult(List<Map<String, Object>> data) {
        try {
            String boundary = ContentSanitizer.generateBoundary();
            var sanitized = ContentSanitizer.sanitizeRecords(data, boundary);
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(sanitized);
            String securityContext = ContentSanitizer.buildSecurityContext(boundary);
            return CallToolResult.builder()
                    .content(List.of(
                            new McpSchema.TextContent(securityContext),
                            new McpSchema.TextContent(json)))
                    .isError(false)
                    .build();
        } catch (Exception e) {
            return errorResult("Failed to serialise result: " + e.getMessage());
        }
    }

    static CallToolResult jsonResult(Object data) {
        try {
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            return CallToolResult.builder()
                    .addTextContent(json)
                    .isError(false)
                    .build();
        } catch (Exception e) {
            return errorResult("Failed to serialise result: " + e.getMessage());
        }
    }

    static CallToolResult errorResult(String message) {
        return CallToolResult.builder()
                .addTextContent(message)
                .isError(true)
                .build();
    }

    static String getString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return String.valueOf(value);
    }

    static int getInt(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(value));
    }
}
