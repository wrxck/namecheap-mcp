package com.namecheap.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.namecheap.mcp.ResultHelper.*;

final class NamecheapTools {

    private NamecheapTools() {}

    static McpServerFeatures.SyncToolSpecification listDomains(NamecheapClient client) {
        var schema = schema(Map.of(
                "page", Map.of("type", "integer", "description", "Page number (default 1)"),
                "pageSize", Map.of("type", "integer", "description", "Results per page, max 100 (default 20)")),
                Collections.emptyList());

        var tool = McpSchema.Tool.builder()
                .name("list_domains")
                .description("List all domains on the Namecheap account. Returns domain names, expiry dates, and status.")
                .inputSchema(schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var args = request.arguments() != null ? request.arguments() : Map.<String, Object>of();
                        int page = getInt(args, "page", 1);
                        int pageSize = getInt(args, "pageSize", 20);
                        var domains = client.listDomains(page, pageSize);
                        return sanitizedResult(domains);
                    } catch (Exception e) {
                        return errorResult(e.getMessage());
                    }
                })
                .build();
    }

    static McpServerFeatures.SyncToolSpecification getDomainInfo(NamecheapClient client) {
        var schema = sldTldSchema();

        var tool = McpSchema.Tool.builder()
                .name("get_domain_info")
                .description("Get detailed information for a specific domain including status, DNS details, and nameservers.")
                .inputSchema(schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var args = request.arguments();
                        String sld = getString(args, "sld");
                        String tld = getString(args, "tld");
                        var info = client.getDomainInfo(sld, tld);
                        return sanitizedResult(List.of(info));
                    } catch (Exception e) {
                        return errorResult(e.getMessage());
                    }
                })
                .build();
    }

    static McpServerFeatures.SyncToolSpecification getDnsHosts(NamecheapClient client) {
        var schema = sldTldSchema();

        var tool = McpSchema.Tool.builder()
                .name("get_dns_hosts")
                .description("Get all DNS host records for a domain. Returns record type, hostname, address, TTL, and MX priority.")
                .inputSchema(schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var args = request.arguments();
                        String sld = getString(args, "sld");
                        String tld = getString(args, "tld");
                        var hosts = client.getDnsHosts(sld, tld);
                        return sanitizedResult(hosts);
                    } catch (Exception e) {
                        return errorResult(e.getMessage());
                    }
                })
                .build();
    }

    static McpServerFeatures.SyncToolSpecification getNameservers(NamecheapClient client) {
        var schema = sldTldSchema();

        var tool = McpSchema.Tool.builder()
                .name("get_nameservers")
                .description("Get the nameservers configured for a domain.")
                .inputSchema(schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var args = request.arguments();
                        String sld = getString(args, "sld");
                        String tld = getString(args, "tld");
                        var ns = client.getNameservers(sld, tld);
                        return sanitizedResult(List.of(ns));
                    } catch (Exception e) {
                        return errorResult(e.getMessage());
                    }
                })
                .build();
    }

    static McpServerFeatures.SyncToolSpecification setDnsHosts(NamecheapClient client) {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("sld", Map.of("type", "string", "description", "Second-level domain"));
        props.put("tld", Map.of("type", "string", "description", "Top-level domain"));
        props.put("records", Map.of("type", "array", "description",
                "Array of DNS records. Each record: {name, type, address, ttl?, mxPref?}",
                "items", Map.of("type", "object")));
        var schema = schema(props, List.of("sld", "tld", "records"));

        var tool = McpSchema.Tool.builder()
                .name("set_dns_hosts")
                .description("WARNING: This REPLACES ALL existing DNS records for the domain. " +
                        "Use add_dns_record or remove_dns_record for individual changes. " +
                        "Provide the complete list of records you want the domain to have. " +
                        "DNS changes may take time to propagate.")
                .inputSchema(schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var args = request.arguments();
                        String sld = getString(args, "sld");
                        String tld = getString(args, "tld");

                        var previousRecords = client.getDnsHosts(sld, tld);

                        @SuppressWarnings("unchecked")
                        var rawRecords = (List<Map<String, Object>>) args.get("records");
                        var records = new ArrayList<Map<String, String>>();
                        for (var m : rawRecords) {
                            var stringMap = new LinkedHashMap<String, String>();
                            m.forEach((k, v) -> stringMap.put(k, String.valueOf(v)));
                            records.add(stringMap);
                        }

                        var result = client.setDnsHosts(sld, tld, records);
                        result.put("previousRecords", previousRecords);
                        return jsonResult(result);
                    } catch (Exception e) {
                        return errorResult(e.getMessage());
                    }
                })
                .build();
    }

    static McpServerFeatures.SyncToolSpecification addDnsRecord(NamecheapClient client) {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("sld", Map.of("type", "string", "description", "Second-level domain"));
        props.put("tld", Map.of("type", "string", "description", "Top-level domain"));
        props.put("name", Map.of("type", "string", "description", "Hostname (e.g. '@' for root, 'www', 'mail')"));
        props.put("type", Map.of("type", "string", "description", "Record type: A, AAAA, CNAME, MX, MXE, TXT, URL, URL301, FRAME"));
        props.put("address", Map.of("type", "string", "description", "Record value (IP address, hostname, or text)"));
        props.put("ttl", Map.of("type", "integer", "description", "TTL in seconds, 60-60000 (default 1800)"));
        props.put("mxPref", Map.of("type", "integer", "description", "MX priority, 0-65535 (default 10, only for MX/MXE)"));
        var schema = schema(props, List.of("sld", "tld", "name", "type", "address"));

        var tool = McpSchema.Tool.builder()
                .name("add_dns_record")
                .description("Add a single DNS record to a domain. Fetches existing records first and appends the new one, " +
                        "so existing records are preserved. DNS changes may take time to propagate.")
                .inputSchema(schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var args = request.arguments();
                        String sld = getString(args, "sld");
                        String tld = getString(args, "tld");

                        var currentHosts = client.getDnsHosts(sld, tld);
                        var allRecords = convertHostsToRecords(currentHosts);

                        var newRecord = new LinkedHashMap<String, String>();
                        newRecord.put("name", getString(args, "name"));
                        newRecord.put("type", getString(args, "type"));
                        newRecord.put("address", getString(args, "address"));
                        newRecord.put("ttl", String.valueOf(getInt(args, "ttl", 1800)));
                        if (args.containsKey("mxPref")) {
                            newRecord.put("mxPref", String.valueOf(getInt(args, "mxPref", 10)));
                        }
                        allRecords.add(newRecord);

                        var result = client.setDnsHosts(sld, tld, allRecords);
                        result.put("addedRecord", newRecord);
                        result.put("previousRecords", currentHosts);
                        return jsonResult(result);
                    } catch (Exception e) {
                        return errorResult(e.getMessage());
                    }
                })
                .build();
    }

    static McpServerFeatures.SyncToolSpecification removeDnsRecord(NamecheapClient client) {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("sld", Map.of("type", "string", "description", "Second-level domain"));
        props.put("tld", Map.of("type", "string", "description", "Top-level domain"));
        props.put("name", Map.of("type", "string", "description", "Hostname to match (e.g. '@', 'www')"));
        props.put("type", Map.of("type", "string", "description", "Record type to match (e.g. 'A', 'TXT')"));
        props.put("address", Map.of("type", "string", "description", "Record value to match (optional, for disambiguation)"));
        var schema = schema(props, List.of("sld", "tld", "name", "type"));

        var tool = McpSchema.Tool.builder()
                .name("remove_dns_record")
                .description("Remove a DNS record from a domain. Fetches existing records, removes the matching one, " +
                        "and sets the remaining records. DNS changes may take time to propagate.")
                .inputSchema(schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var args = request.arguments();
                        String sld = getString(args, "sld");
                        String tld = getString(args, "tld");
                        String matchName = getString(args, "name");
                        String matchType = getString(args, "type").toUpperCase();
                        String matchAddress = args.containsKey("address") ? getString(args, "address") : null;

                        var currentHosts = client.getDnsHosts(sld, tld);

                        var remaining = new ArrayList<Map<String, String>>();
                        var removed = new ArrayList<Map<String, Object>>();

                        for (var host : currentHosts) {
                            String hostName = String.valueOf(host.get("name"));
                            String hostType = String.valueOf(host.get("type"));
                            String hostAddress = String.valueOf(host.get("address"));

                            boolean matches = hostName.equalsIgnoreCase(matchName)
                                    && hostType.equalsIgnoreCase(matchType);
                            if (matchAddress != null) {
                                matches = matches && hostAddress.equalsIgnoreCase(matchAddress);
                            }

                            if (matches) {
                                removed.add(host);
                            } else {
                                var record = new LinkedHashMap<String, String>();
                                record.put("name", hostName);
                                record.put("type", hostType);
                                record.put("address", hostAddress);
                                record.put("ttl", String.valueOf(host.get("ttl")));
                                String mxPref = String.valueOf(host.get("mxPref"));
                                if (mxPref != null && !mxPref.equals("0") && !mxPref.equals("null")) {
                                    record.put("mxPref", mxPref);
                                }
                                remaining.add(record);
                            }
                        }

                        if (removed.isEmpty()) {
                            return errorResult("No matching record found for name=%s type=%s%s"
                                    .formatted(matchName, matchType,
                                            matchAddress != null ? " address=" + matchAddress : ""));
                        }

                        Map<String, Object> result;
                        if (remaining.isEmpty()) {
                            result = new LinkedHashMap<>();
                            result.put("isSuccess", false);
                            result.put("error", "Cannot remove all DNS records. At least one record must remain.");
                            result.put("removedCount", 0);
                        } else {
                            result = client.setDnsHosts(sld, tld, remaining);
                        }
                        result.put("removedRecords", removed);
                        result.put("previousRecords", currentHosts);
                        return jsonResult(result);
                    } catch (Exception e) {
                        return errorResult(e.getMessage());
                    }
                })
                .build();
    }

    static McpServerFeatures.SyncToolSpecification setNameservers(NamecheapClient client) {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("sld", Map.of("type", "string", "description", "Second-level domain"));
        props.put("tld", Map.of("type", "string", "description", "Top-level domain"));
        props.put("nameservers", Map.of("type", "array", "description",
                "List of nameserver hostnames. Empty array or omit to revert to Namecheap default DNS.",
                "items", Map.of("type", "string")));
        var schema = schema(props, List.of("sld", "tld"));

        var tool = McpSchema.Tool.builder()
                .name("set_nameservers")
                .description("WARNING: Set custom nameservers for a domain, or revert to Namecheap default DNS. " +
                        "Changing nameservers affects ALL DNS resolution for the domain and may take 24-48 hours to propagate.")
                .inputSchema(schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var args = request.arguments();
                        String sld = getString(args, "sld");
                        String tld = getString(args, "tld");

                        var previousNs = client.getNameservers(sld, tld);

                        @SuppressWarnings("unchecked")
                        List<String> nameservers = args.containsKey("nameservers")
                                ? new ArrayList<>(((List<Object>) args.get("nameservers")).stream()
                                        .map(String::valueOf).toList())
                                : List.of();

                        var result = client.setNameservers(sld, tld, nameservers);
                        result.put("previousNameservers", previousNs);
                        return jsonResult(result);
                    } catch (Exception e) {
                        return errorResult(e.getMessage());
                    }
                })
                .build();
    }

    static McpServerFeatures.SyncToolSpecification getDnsSec(NamecheapClient client) {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("sld", Map.of("type", "string", "description", "Second-level domain"));
        props.put("tld", Map.of("type", "string", "description", "Top-level domain"));
        props.put("isHosted", Map.of("type", "boolean",
                "description", "True if domain uses Namecheap nameservers, false if external (e.g. Cloudflare)"));
        var schema = schema(props, List.of("sld", "tld"));

        var tool = McpSchema.Tool.builder()
                .name("get_dnssec")
                .description("Check whether DNSSEC is supported and enabled for a domain. " +
                        "Uses the namecheap.domains.dnssec.getList endpoint.")
                .inputSchema(schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var args = request.arguments();
                        String sld = getString(args, "sld");
                        String tld = getString(args, "tld");
                        boolean isHosted = args.containsKey("isHosted")
                                && Boolean.parseBoolean(String.valueOf(args.get("isHosted")));
                        var info = client.getDnsSec(sld, tld, isHosted);
                        return jsonResult(info);
                    } catch (Exception e) {
                        return errorResult(e.getMessage());
                    }
                })
                .build();
    }

    static McpServerFeatures.SyncToolSpecification addDnsSecRecord(NamecheapApClient apClient) {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("domain", Map.of("type", "string", "description", "Full domain name (e.g. example.com)"));
        props.put("keyTag", Map.of("type", "integer", "description", "DS key tag (uint16)"));
        props.put("algorithm", Map.of("type", "integer",
                "description", "DNSSEC algorithm (e.g. 13 for ECDSAP256SHA256, 8 for RSASHA256)"));
        props.put("digestType", Map.of("type", "integer", "description", "Digest type (1=SHA1, 2=SHA256, 4=SHA384)"));
        props.put("digest", Map.of("type", "string", "description", "Digest hex string"));
        props.put("isHosted", Map.of("type", "boolean",
                "description", "True if using Namecheap nameservers, false if external (default false)"));
        var schema = schema(props, List.of("domain", "keyTag", "algorithm", "digestType", "digest"));

        var tool = McpSchema.Tool.builder()
                .name("add_dnssec_record")
                .description("Add a DS record to a domain at the registrar. " +
                        "Uses the Namecheap admin-panel endpoint (not the public API). " +
                        "Requires AP cookies in ~/.namecheap-mcp/ap-cookies.txt — see file for refresh notes.")
                .inputSchema(schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var args = request.arguments();
                        String domain = getString(args, "domain");
                        int keyTag = getInt(args, "keyTag", 0);
                        int algorithm = getInt(args, "algorithm", 0);
                        int digestType = getInt(args, "digestType", 0);
                        String digest = getString(args, "digest");
                        boolean isHosted = args.containsKey("isHosted")
                                && Boolean.parseBoolean(String.valueOf(args.get("isHosted")));
                        var result = apClient.addDnsSecRecord(domain, isHosted, keyTag, algorithm, digestType, digest);
                        return jsonResult(result);
                    } catch (Exception e) {
                        return errorResult(e.getMessage());
                    }
                })
                .build();
    }

    static McpServerFeatures.SyncToolSpecification removeDnsSecRecord(NamecheapApClient apClient) {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("domain", Map.of("type", "string", "description", "Full domain name"));
        props.put("recordId", Map.of("type", "integer",
                "description", "ID of the DS record to remove (from the AP UI/API)"));
        props.put("isHosted", Map.of("type", "boolean",
                "description", "True if using Namecheap nameservers, false if external (default false)"));
        var schema = schema(props, List.of("domain", "recordId"));

        var tool = McpSchema.Tool.builder()
                .name("remove_dnssec_record")
                .description("Remove a DS record from a domain. " +
                        "Uses the Namecheap admin-panel endpoint and requires AP cookies. " +
                        "Endpoint shape inferred — may need adjustment if Namecheap changes their UI.")
                .inputSchema(schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var args = request.arguments();
                        String domain = getString(args, "domain");
                        int recordId = getInt(args, "recordId", 0);
                        boolean isHosted = args.containsKey("isHosted")
                                && Boolean.parseBoolean(String.valueOf(args.get("isHosted")));
                        var result = apClient.removeDnsSecRecord(domain, isHosted, recordId);
                        return jsonResult(result);
                    } catch (Exception e) {
                        return errorResult(e.getMessage());
                    }
                })
                .build();
    }

    @SuppressWarnings("unchecked")
    private static McpSchema.JsonSchema schema(Map<String, ?> properties, List<String> required) {
        return new McpSchema.JsonSchema("object",
                (Map<String, Object>) (Map<?, ?>) properties, required, false, null, null);
    }

    private static McpSchema.JsonSchema sldTldSchema() {
        return schema(Map.of(
                "sld", Map.of("type", "string", "description", "Second-level domain (e.g. 'example' for example.com)"),
                "tld", Map.of("type", "string", "description", "Top-level domain (e.g. 'com' for example.com)")),
                List.of("sld", "tld"));
    }

    private static ArrayList<Map<String, String>> convertHostsToRecords(List<Map<String, Object>> hosts) {
        var records = new ArrayList<Map<String, String>>();
        for (var host : hosts) {
            var record = new LinkedHashMap<String, String>();
            record.put("name", String.valueOf(host.get("name")));
            record.put("type", String.valueOf(host.get("type")));
            record.put("address", String.valueOf(host.get("address")));
            record.put("ttl", String.valueOf(host.get("ttl")));
            String mxPref = String.valueOf(host.get("mxPref"));
            if (mxPref != null && !mxPref.equals("0") && !mxPref.equals("null")) {
                record.put("mxPref", mxPref);
            }
            records.add(record);
        }
        return records;
    }
}
