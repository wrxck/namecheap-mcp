package com.namecheap.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class NamecheapClient {

    private static final Logger log = LoggerFactory.getLogger(NamecheapClient.class);

    private static final Pattern SLD_PATTERN = Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$");
    private static final Pattern TLD_PATTERN = Pattern.compile("^[a-zA-Z]{2,}(\\.[a-zA-Z]{2,})?$");
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");
    private static final Pattern IPV6_PATTERN = Pattern.compile("^[0-9a-fA-F:]+$");
    private static final Set<String> VALID_RECORD_TYPES = Set.of(
            "A", "AAAA", "CNAME", "MX", "MXE", "TXT", "URL", "URL301", "FRAME");

    private static final int MIN_TTL = 60;
    private static final int MAX_TTL = 60000;
    private static final int MAX_MX_PREF = 65535;

    private final NamecheapAuth.Config config;
    private final RateLimiter rateLimiter;
    private final HttpClient httpClient;
    private final DocumentBuilderFactory dbFactory;

    public NamecheapClient(String apiUser, String apiKey, String clientIp, RateLimiter rateLimiter) {
        this(new NamecheapAuth.Config(apiUser, apiKey, apiUser, clientIp, false), rateLimiter);
    }

    NamecheapClient(NamecheapAuth.Config config, RateLimiter rateLimiter) {
        this.config = config;
        this.rateLimiter = rateLimiter;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.dbFactory = createSafeDocumentBuilderFactory();
    }

    private static DocumentBuilderFactory createSafeDocumentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure XML parser security", e);
        }
        return factory;
    }

    // --- public API methods ---

    public List<Map<String, Object>> listDomains(int page, int pageSize) {
        var params = Map.of(
                "PageSize", String.valueOf(Math.min(Math.max(pageSize, 1), 100)),
                "Page", String.valueOf(Math.max(page, 1)));
        Document doc = execute("namecheap.domains.getList", params);

        var result = new ArrayList<Map<String, Object>>();
        NodeList domains = doc.getElementsByTagName("Domain");
        for (int i = 0; i < domains.getLength(); i++) {
            Element el = (Element) domains.item(i);
            var domain = new LinkedHashMap<String, Object>();
            domain.put("domainName", el.getAttribute("Name"));
            domain.put("expires", el.getAttribute("Expires"));
            domain.put("isExpired", el.getAttribute("IsExpired"));
            domain.put("autoRenew", el.getAttribute("AutoRenew"));
            domain.put("whoisGuard", el.getAttribute("WhoisGuard"));
            domain.put("isPremium", el.getAttribute("IsPremium"));
            result.add(domain);
        }

        Element paging = getFirstElement(doc, "Paging");
        if (paging != null) {
            var meta = new LinkedHashMap<String, Object>();
            meta.put("totalItems", getElementText(paging, "TotalItems"));
            meta.put("currentPage", getElementText(paging, "CurrentPage"));
            meta.put("pageSize", getElementText(paging, "PageSize"));
            result.addFirst(Map.of("_paging", meta));
        }
        return result;
    }

    public Map<String, Object> getDomainInfo(String sld, String tld) {
        validateDomainParts(sld, tld);
        var params = Map.of("DomainName", sld + "." + tld);
        Document doc = execute("namecheap.domains.getInfo", params);

        Element domainInfo = getFirstElement(doc, "DomainGetInfoResult");
        if (domainInfo == null) {
            throw new NamecheapApiException("No DomainGetInfoResult in response");
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("domainName", domainInfo.getAttribute("DomainName"));
        result.put("status", domainInfo.getAttribute("Status"));
        result.put("isOwner", domainInfo.getAttribute("IsOwner"));

        Element dnsDetails = getFirstElement(doc, "DnsDetails");
        if (dnsDetails != null) {
            var dns = new LinkedHashMap<String, Object>();
            dns.put("providerType", dnsDetails.getAttribute("ProviderType"));
            dns.put("isUsingOurDNS", dnsDetails.getAttribute("IsUsingOurDNS"));
            var nameservers = new ArrayList<String>();
            NodeList nsList = dnsDetails.getElementsByTagName("Nameserver");
            for (int i = 0; i < nsList.getLength(); i++) {
                nameservers.add(nsList.item(i).getTextContent().trim());
            }
            dns.put("nameservers", nameservers);
            result.put("dnsDetails", dns);
        }

        Element modificationRights = getFirstElement(doc, "Modificationrights");
        if (modificationRights != null) {
            result.put("canModifyAll", modificationRights.getAttribute("All"));
        }

        return result;
    }

    public List<Map<String, Object>> getDnsHosts(String sld, String tld) {
        validateDomainParts(sld, tld);
        var params = Map.of("SLD", sld, "TLD", tld);
        Document doc = execute("namecheap.domains.dns.getHosts", params);

        var result = new ArrayList<Map<String, Object>>();
        NodeList hosts = doc.getElementsByTagName("host");
        for (int i = 0; i < hosts.getLength(); i++) {
            Element el = (Element) hosts.item(i);
            var record = new LinkedHashMap<String, Object>();
            record.put("hostId", el.getAttribute("HostId"));
            record.put("name", el.getAttribute("Name"));
            record.put("type", el.getAttribute("Type"));
            record.put("address", el.getAttribute("Address"));
            record.put("mxPref", el.getAttribute("MXPref"));
            record.put("ttl", el.getAttribute("TTL"));
            record.put("isActive", el.getAttribute("IsActive"));
            record.put("isDDNSEnabled", el.getAttribute("IsDDNSEnabled"));
            result.add(record);
        }
        return result;
    }

    public Map<String, Object> getNameservers(String sld, String tld) {
        validateDomainParts(sld, tld);
        var params = Map.of("SLD", sld, "TLD", tld);
        Document doc = execute("namecheap.domains.dns.getList", params);

        Element dnsResult = getFirstElement(doc, "DomainDNSGetListResult");
        if (dnsResult == null) {
            throw new NamecheapApiException("No DomainDNSGetListResult in response");
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("domainName", dnsResult.getAttribute("Domain"));
        result.put("isUsingOurDNS", dnsResult.getAttribute("IsUsingOurDNS"));

        var nameservers = new ArrayList<String>();
        NodeList nsList = dnsResult.getElementsByTagName("Nameserver");
        for (int i = 0; i < nsList.getLength(); i++) {
            nameservers.add(nsList.item(i).getTextContent().trim());
        }
        result.put("nameservers", nameservers);
        return result;
    }

    public Map<String, Object> setDnsHosts(String sld, String tld, List<Map<String, String>> records) {
        validateDomainParts(sld, tld);
        var params = new LinkedHashMap<String, String>();
        params.put("SLD", sld);
        params.put("TLD", tld);

        for (int i = 0; i < records.size(); i++) {
            Map<String, String> record = records.get(i);
            int idx = i + 1;

            String type = record.get("type");
            if (type == null || !VALID_RECORD_TYPES.contains(type.toUpperCase())) {
                throw new IllegalArgumentException("Invalid record type: " + type);
            }
            type = type.toUpperCase();

            String hostName = record.getOrDefault("name", "@");
            String address = record.get("address");
            if (address == null || address.isBlank()) {
                throw new IllegalArgumentException("Record " + idx + ": address is required");
            }

            validateAddress(type, address);

            int ttl = 1800;
            if (record.containsKey("ttl")) {
                ttl = Integer.parseInt(record.get("ttl"));
                if (ttl < MIN_TTL || ttl > MAX_TTL) {
                    throw new IllegalArgumentException(
                            "Record " + idx + ": TTL must be between " + MIN_TTL + " and " + MAX_TTL);
                }
            }

            params.put("HostName" + idx, hostName);
            params.put("RecordType" + idx, type);
            params.put("Address" + idx, address);
            params.put("TTL" + idx, String.valueOf(ttl));

            if ("MX".equals(type) || "MXE".equals(type)) {
                int mxPref = 10;
                if (record.containsKey("mxPref")) {
                    mxPref = Integer.parseInt(record.get("mxPref"));
                    if (mxPref < 0 || mxPref > MAX_MX_PREF) {
                        throw new IllegalArgumentException(
                                "Record " + idx + ": MXPref must be between 0 and " + MAX_MX_PREF);
                    }
                }
                params.put("MXPref" + idx, String.valueOf(mxPref));
            }
        }

        Document doc = execute("namecheap.domains.dns.setHosts", params);
        Element result = getFirstElement(doc, "DomainDNSSetHostsResult");
        var response = new LinkedHashMap<String, Object>();
        response.put("isSuccess", result != null && "true".equalsIgnoreCase(result.getAttribute("IsSuccess")));
        response.put("domain", sld + "." + tld);
        response.put("recordCount", records.size());
        return response;
    }

    public Map<String, Object> getDnsSec(String sld, String tld, boolean isHosted) {
        validateDomainParts(sld, tld);
        var params = new LinkedHashMap<String, String>();
        params.put("DomainName", sld + "." + tld);
        params.put("SLD", sld);
        params.put("TLD", tld);
        params.put("IsHosted", String.valueOf(isHosted));
        Document doc = execute("namecheap.domains.dnssec.getList", params);
        Element result = getFirstElement(doc, "DnsSecGetListResult");
        var response = new LinkedHashMap<String, Object>();
        if (result != null) {
            response.put("domain", result.getAttribute("Domain"));
            response.put("isSuccess", "true".equalsIgnoreCase(result.getAttribute("IsSuccess")));
            response.put("supported", "true".equalsIgnoreCase(getElementText(result, "supported")));
            response.put("enabled", "true".equalsIgnoreCase(getElementText(result, "enabled")));
        }
        return response;
    }

    public Map<String, Object> setNameservers(String sld, String tld, List<String> nameservers) {
        validateDomainParts(sld, tld);
        var params = new LinkedHashMap<String, String>();
        params.put("SLD", sld);
        params.put("TLD", tld);

        if (nameservers == null || nameservers.isEmpty()) {
            Document doc = execute("namecheap.domains.dns.setDefault", params);
            Element result = getFirstElement(doc, "DomainDNSSetDefaultResult");
            var response = new LinkedHashMap<String, Object>();
            response.put("isSuccess", result != null && "true".equalsIgnoreCase(result.getAttribute("Updated")));
            response.put("domain", sld + "." + tld);
            response.put("action", "setDefault");
            return response;
        }

        params.put("Nameservers", String.join(",", nameservers));
        Document doc = execute("namecheap.domains.dns.setCustom", params);
        Element result = getFirstElement(doc, "DomainDNSSetCustomResult");
        var response = new LinkedHashMap<String, Object>();
        response.put("isSuccess", result != null && "true".equalsIgnoreCase(result.getAttribute("Update")));
        response.put("domain", sld + "." + tld);
        response.put("nameservers", nameservers);
        return response;
    }

    // --- Domain-based convenience methods (take full domain, split internally) ---

    private static final Set<String> MULTI_PART_TLDS = Set.of(
            "co.uk", "org.uk", "me.uk", "net.uk", "ac.uk",
            "co.nz", "co.za", "com.au", "net.au", "org.au",
            "co.in", "com.br", "co.jp");

    public static String[] splitDomain(String domain) {
        String d = domain.toLowerCase().trim();
        String[] parts = d.split("\\.");
        if (parts.length < 2) throw new IllegalArgumentException("Invalid domain: " + domain);
        if (parts.length >= 3) {
            String possibleTld = parts[parts.length - 2] + "." + parts[parts.length - 1];
            if (MULTI_PART_TLDS.contains(possibleTld)) {
                String sld = String.join(".", java.util.Arrays.copyOf(parts, parts.length - 2));
                return new String[]{sld, possibleTld};
            }
        }
        String sld = String.join(".", java.util.Arrays.copyOf(parts, parts.length - 1));
        return new String[]{sld, parts[parts.length - 1]};
    }

    public List<Map<String, Object>> listAllDomains() {
        var allDomains = new ArrayList<Map<String, Object>>();
        int page = 1;
        while (true) {
            var domains = listDomains(page, 100);
            // Remove paging metadata if present
            var filtered = domains.stream()
                    .filter(d -> !d.containsKey("_paging"))
                    .toList();
            allDomains.addAll(filtered);
            if (filtered.size() < 100) break;
            page++;
        }
        return allDomains;
    }

    public List<Map<String, Object>> getDnsHostsByDomain(String domain) {
        String[] parts = splitDomain(domain);
        return getDnsHosts(parts[0], parts[1]);
    }

    public Map<String, Object> getNameserversByDomain(String domain) {
        String[] parts = splitDomain(domain);
        return getNameservers(parts[0], parts[1]);
    }

    public Map<String, Object> setNameserversByDomain(String domain, List<String> nameservers) {
        String[] parts = splitDomain(domain);
        return setNameservers(parts[0], parts[1], nameservers);
    }

    // --- internal helpers ---

    Document execute(String command, Map<String, String> params) {
        rateLimiter.checkAndRecord();

        var urlBuilder = new StringBuilder(config.baseUrl());
        urlBuilder.append("?ApiUser=").append(encode(config.apiUser()));
        urlBuilder.append("&ApiKey=").append(encode(config.apiKey()));
        urlBuilder.append("&UserName=").append(encode(config.userName()));
        urlBuilder.append("&ClientIp=").append(encode(config.clientIp()));
        urlBuilder.append("&Command=").append(encode(command));

        for (var entry : params.entrySet()) {
            urlBuilder.append("&").append(encode(entry.getKey()))
                    .append("=").append(encode(entry.getValue()));
        }

        String url = urlBuilder.toString();
        log.debug("Executing: {} ({})", command, params.keySet());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new NamecheapApiException(
                        "HTTP %d from Namecheap API".formatted(response.statusCode()));
            }

            Document doc = parseXml(response.body());
            checkApiErrors(doc);
            return doc;

        } catch (NamecheapApiException e) {
            throw e;
        } catch (Exception e) {
            throw new NamecheapApiException("API call failed: " + e.getMessage(), e);
        }
    }

    private Document parseXml(String xml) throws Exception {
        var builder = dbFactory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private void checkApiErrors(Document doc) {
        Element root = doc.getDocumentElement();
        String status = root.getAttribute("Status");
        if ("ERROR".equalsIgnoreCase(status)) {
            NodeList errors = doc.getElementsByTagName("Error");
            if (errors.getLength() > 0) {
                Element error = (Element) errors.item(0);
                String code = error.getAttribute("Number");
                String message = error.getTextContent().trim();
                throw new NamecheapApiException("Namecheap API error %s: %s".formatted(code, message));
            }
            throw new NamecheapApiException("Namecheap API returned error status");
        }
    }

    private void validateDomainParts(String sld, String tld) {
        if (sld == null || !SLD_PATTERN.matcher(sld).matches()) {
            throw new IllegalArgumentException("Invalid SLD: " + sld);
        }
        if (tld == null || !TLD_PATTERN.matcher(tld).matches()) {
            throw new IllegalArgumentException("Invalid TLD: " + tld);
        }
    }

    private void validateAddress(String type, String address) {
        switch (type) {
            case "A" -> {
                if (!IPV4_PATTERN.matcher(address).matches()) {
                    throw new IllegalArgumentException("Invalid IPv4 address for A record: " + address);
                }
            }
            case "AAAA" -> {
                if (!IPV6_PATTERN.matcher(address).matches()) {
                    throw new IllegalArgumentException("Invalid IPv6 address for AAAA record: " + address);
                }
            }
            default -> {
                // CNAME, MX, TXT, URL etc. — just ensure non-empty (already checked)
            }
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Element getFirstElement(Document doc, String tagName) {
        NodeList list = doc.getElementsByTagName(tagName);
        return list.getLength() > 0 ? (Element) list.item(0) : null;
    }

    private static String getElementText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        return list.getLength() > 0 ? list.item(0).getTextContent().trim() : "";
    }

    public static final class NamecheapApiException extends RuntimeException {
        NamecheapApiException(String message) {
            super(message);
        }
        NamecheapApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
