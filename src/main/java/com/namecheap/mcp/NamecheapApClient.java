package com.namecheap.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// thin client for the namecheap admin-panel (ap.www.namecheap.com) endpoints
// these are not in the public api. they need a logged-in session cookie jar
// plus the _nccompliance header (matches the _NcCompliance cookie value).
// cookies typically expire ~2 hours after capture; refresh from devtools.
public final class NamecheapApClient {

    private static final Logger log = LoggerFactory.getLogger(NamecheapApClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Path COOKIE_PATH = Path.of(
            System.getProperty("user.home"), ".namecheap-mcp", "ap-cookies.txt");

    private static final String ADD_URL =
            "https://ap.www.namecheap.com/Domains/dns/AddDnsSecRecords";
    private static final String DELETE_URL =
            "https://ap.www.namecheap.com/Domains/dns/DeleteDnsSecRecords";

    private final HttpClient httpClient;

    public NamecheapApClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Map<String, Object> addDnsSecRecord(
            String domain, boolean isHosted, int keyTag, int algorithm, int digestType, String digest) {

        var record = new LinkedHashMap<String, Object>();
        record.put("KeyTag", keyTag);
        record.put("Digest", digest);
        record.put("AlgorythmType", algorithm);
        record.put("DigestType", digestType);

        var body = new LinkedHashMap<String, Object>();
        body.put("domainName", domain);
        body.put("isHosted", isHosted);
        body.put("recordsToAdd", List.of(record));

        return postJson(ADD_URL, domain, body);
    }

    public Map<String, Object> removeDnsSecRecord(String domain, boolean isHosted, int recordId) {
        var body = new LinkedHashMap<String, Object>();
        body.put("domainName", domain);
        body.put("isHosted", isHosted);
        body.put("recordsToDelete", List.of(Map.of("Id", recordId)));
        return postJson(DELETE_URL, domain, body);
    }

    private Map<String, Object> postJson(String url, String domain, Map<String, Object> body) {
        var auth = loadAuth();
        try {
            String json = OBJECT_MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("content-type", "application/json;charset=UTF-8")
                    .header("accept", "application/json, text/plain, */*")
                    .header("_nccompliance", auth.compliance())
                    .header("Referer",
                            "https://ap.www.namecheap.com/Domains/DomainControlPanel/" + domain + "/advancedns")
                    .header("cookie", auth.cookies())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String responseBody = response.body();

            var result = new LinkedHashMap<String, Object>();
            result.put("httpStatus", status);

            if (status == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = OBJECT_MAPPER.readValue(responseBody, Map.class);
                result.putAll(parsed);
            } else {
                result.put("rawBody", responseBody);
            }
            return result;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("AP request failed: " + e.getMessage(), e);
        }
    }

    private record ApAuth(String cookies, String compliance) {}

    private ApAuth loadAuth() {
        if (!Files.exists(COOKIE_PATH)) {
            throw new IllegalStateException(
                    "AP cookie file not found at " + COOKIE_PATH + ". "
                    + "capture the cookie header from https://ap.www.namecheap.com (devtools > network > "
                    + "any ap request > headers > cookie) and write it to that file as a single line. "
                    + "cookies expire ~2h after capture, so refresh as needed.");
        }
        try {
            String cookies = Files.readString(COOKIE_PATH).trim();
            String compliance = extractCookie(cookies, "_NcCompliance");
            if (compliance == null) {
                throw new IllegalStateException(
                        "_NcCompliance cookie not found in " + COOKIE_PATH);
            }
            return new ApAuth(cookies, compliance);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read AP cookie file: " + e.getMessage(), e);
        }
    }

    private static String extractCookie(String cookieHeader, String name) {
        for (String pair : cookieHeader.split(";")) {
            String[] kv = pair.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                return kv[1];
            }
        }
        return null;
    }
}
