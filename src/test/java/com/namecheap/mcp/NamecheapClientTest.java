package com.namecheap.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NamecheapClientTest {

    private NamecheapClient createClient() {
        var config = new NamecheapAuth.Config("testuser", "testkey", "testuser", "127.0.0.1", true);
        return new NamecheapClient(config, new RateLimiter());
    }

    @Test
    void rejectsInvalidSld() {
        var client = createClient();
        assertThrows(IllegalArgumentException.class, () -> client.getDnsHosts("-invalid", "com"));
        assertThrows(IllegalArgumentException.class, () -> client.getDnsHosts("", "com"));
        assertThrows(IllegalArgumentException.class, () -> client.getDnsHosts("a b", "com"));
    }

    @Test
    void rejectsInvalidTld() {
        var client = createClient();
        assertThrows(IllegalArgumentException.class, () -> client.getDnsHosts("example", "123"));
        assertThrows(IllegalArgumentException.class, () -> client.getDnsHosts("example", ""));
        assertThrows(IllegalArgumentException.class, () -> client.getDnsHosts("example", "c-m"));
    }

    @Test
    void acceptsValidDomainParts() {
        var client = createClient();
        // these will fail at the HTTP level but should pass validation
        assertThrows(NamecheapClient.NamecheapApiException.class,
                () -> client.getDnsHosts("example", "com"));
        assertThrows(NamecheapClient.NamecheapApiException.class,
                () -> client.getDnsHosts("my-domain", "co.uk"));
    }

    @Test
    void acceptsValidSingleCharSld() {
        var client = createClient();
        assertThrows(NamecheapClient.NamecheapApiException.class,
                () -> client.getDnsHosts("x", "com"));
    }

    @Test
    void setDnsHosts_rejectsInvalidRecordType() {
        var client = createClient();
        var records = java.util.List.of(
                java.util.Map.of("name", "@", "type", "INVALID", "address", "1.2.3.4"));
        assertThrows(IllegalArgumentException.class,
                () -> client.setDnsHosts("example", "com", records));
    }

    @Test
    void setDnsHosts_rejectsInvalidIpForARecord() {
        var client = createClient();
        var records = java.util.List.of(
                java.util.Map.of("name", "@", "type", "A", "address", "not-an-ip"));
        assertThrows(IllegalArgumentException.class,
                () -> client.setDnsHosts("example", "com", records));
    }

    @Test
    void setDnsHosts_rejectsTtlOutOfRange() {
        var client = createClient();
        var records = java.util.List.of(
                java.util.Map.of("name", "@", "type", "A", "address", "1.2.3.4", "ttl", "10"));
        assertThrows(IllegalArgumentException.class,
                () -> client.setDnsHosts("example", "com", records));
    }

    @Test
    void setDnsHosts_rejectsMissingAddress() {
        var client = createClient();
        var records = java.util.List.of(
                java.util.Map.of("name", "@", "type", "A"));
        assertThrows(IllegalArgumentException.class,
                () -> client.setDnsHosts("example", "com", records));
    }
}
