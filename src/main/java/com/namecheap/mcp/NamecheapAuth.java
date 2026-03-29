package com.namecheap.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;

public final class NamecheapAuth {

    private static final Logger log = LoggerFactory.getLogger(NamecheapAuth.class);
    private static final Path CONFIG_DIR;
    private static final Path CONFIG_FILE;

    static {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("user.home system property is not set");
        }
        CONFIG_DIR = Path.of(home, ".namecheap-mcp");
        CONFIG_FILE = CONFIG_DIR.resolve("config.properties");
    }

    private NamecheapAuth() {}

    public record Config(String apiUser, String apiKey, String userName, String clientIp, boolean useSandbox) {
        String baseUrl() {
            return useSandbox
                    ? "https://api.sandbox.namecheap.com/xml.response"
                    : "https://api.namecheap.com/xml.response";
        }
    }

    static Config getConfig() {
        if (!Files.exists(CONFIG_FILE)) {
            throw new IllegalStateException(
                    "Config not found at " + CONFIG_FILE + ". Run with --init to set up.");
        }

        var props = new Properties();
        try (var reader = Files.newBufferedReader(CONFIG_FILE)) {
            props.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read config: " + e.getMessage(), e);
        }

        String apiUser = requireProp(props, "apiUser");
        String apiKey = requireProp(props, "apiKey");
        String userName = requireProp(props, "userName");
        String clientIp = requireProp(props, "clientIp");
        boolean useSandbox = Boolean.parseBoolean(props.getProperty("useSandbox", "false"));

        return new Config(apiUser, apiKey, userName, clientIp, useSandbox);
    }

    static void init() throws IOException {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("No console available. Run --init from an interactive terminal.");
        }

        System.err.println("Namecheap MCP Server — Initial Setup");
        System.err.println("=====================================");
        System.err.println("You'll need your Namecheap API credentials.");
        System.err.println("Enable API access at: https://ap.www.namecheap.com/settings/tools/apiaccess/");
        System.err.println();

        String apiUser = prompt(console, "API User (your Namecheap username): ");
        String apiKey = prompt(console, "API Key: ");
        String userName = prompt(console, "UserName (usually same as API User) [" + apiUser + "]: ");
        if (userName.isBlank()) {
            userName = apiUser;
        }
        String clientIp = prompt(console, "Whitelisted Client IP: ");
        String sandboxStr = prompt(console, "Use sandbox? (y/N): ");
        boolean useSandbox = sandboxStr.equalsIgnoreCase("y") || sandboxStr.equalsIgnoreCase("yes");

        ensureConfigDir();

        var props = new Properties();
        props.setProperty("apiUser", apiUser);
        props.setProperty("apiKey", apiKey);
        props.setProperty("userName", userName);
        props.setProperty("clientIp", clientIp);
        props.setProperty("useSandbox", String.valueOf(useSandbox));

        try (var writer = Files.newBufferedWriter(CONFIG_FILE)) {
            props.store(writer, "Namecheap MCP Server Configuration");
        }

        Files.setPosixFilePermissions(CONFIG_FILE, PosixFilePermissions.fromString("rw-------"));

        System.err.println();
        System.err.println("Config saved to " + CONFIG_FILE);
        System.err.println("You can now start the MCP server.");
    }

    private static void ensureConfigDir() throws IOException {
        if (!Files.exists(CONFIG_DIR)) {
            Files.createDirectories(CONFIG_DIR);
            Files.setPosixFilePermissions(CONFIG_DIR, PosixFilePermissions.fromString("rwx------"));
            log.info("Created config directory: {}", CONFIG_DIR);
        }
    }

    private static String requireProp(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required config property: " + key + ". Run with --init to reconfigure.");
        }
        return value.trim();
    }

    private static String prompt(Console console, String message) {
        System.err.print(message);
        String line = console.readLine();
        return line == null ? "" : line.trim();
    }
}
