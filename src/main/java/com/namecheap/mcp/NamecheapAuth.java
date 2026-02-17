package com.namecheap.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;

final class NamecheapAuth {

    private static final Logger log = LoggerFactory.getLogger(NamecheapAuth.class);
    private static final Path CONFIG_DIR;
    private static final Path CONFIG_FILE;
    private static final String MCP_SERVER_NAME = "namecheap";

    static {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("user.home system property is not set");
        }
        CONFIG_DIR = Path.of(home, ".namecheap-mcp");
        CONFIG_FILE = CONFIG_DIR.resolve("config.properties");
    }

    private NamecheapAuth() {}

    record Config(String apiUser, String apiKey, String userName, String clientIp, boolean useSandbox) {
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

    static void init(String claudeBinary) throws IOException, InterruptedException {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("No console available. Run --init from an interactive terminal.");
        }

        System.err.println("Namecheap MCP Server — Setup");
        System.err.println("============================");
        System.err.println();

        boolean configExists = Files.exists(CONFIG_FILE);
        if (configExists) {
            System.err.println("[skip] Config already exists at " + CONFIG_FILE);
        } else {
            setupConfig(console);
        }

        System.err.println();
        registerWithClaude(console, claudeBinary);

        System.err.println();
        System.err.println("Done. Restart Claude Code to use the namecheap tools.");
    }

    private static void setupConfig(Console console) throws IOException {
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
        System.err.println("[done] Config saved to " + CONFIG_FILE);
    }

    private static void registerWithClaude(Console console, String claudeBinary)
            throws IOException, InterruptedException {
        if (claudeBinary == null || claudeBinary.isBlank()) {
            claudeBinary = findClaudeBinary();
        }

        if (claudeBinary == null) {
            System.err.println("[skip] Claude Code binary not found. Register manually:");
            printManualRegistration();
            return;
        }

        if (isAlreadyRegistered(claudeBinary)) {
            System.err.println("[skip] Already registered with Claude Code");
            return;
        }

        String jarPath = resolveJarPath();
        System.err.println("Registering with Claude Code...");

        var process = new ProcessBuilder(
                claudeBinary, "mcp", "add",
                "--scope", "user",
                "--transport", "stdio",
                MCP_SERVER_NAME, "--",
                "java", "-jar", jarPath)
                .inheritIO()
                .start();

        int exitCode = process.waitFor();
        if (exitCode == 0) {
            System.err.println("[done] Registered as '" + MCP_SERVER_NAME + "'");
        } else {
            System.err.println("[fail] Registration failed (exit " + exitCode + "). Register manually:");
            printManualRegistration();
        }
    }

    private static String findClaudeBinary() {
        String[] candidates = {
                "claude",
                System.getProperty("user.home") + "/.local/bin/claude",
                "/usr/local/bin/claude",
                "/usr/bin/claude"
        };

        for (String candidate : candidates) {
            try {
                var process = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true)
                        .start();
                int exit = process.waitFor();
                if (exit == 0) {
                    return candidate;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static boolean isAlreadyRegistered(String claudeBinary) {
        try {
            var process = new ProcessBuilder(claudeBinary, "mcp", "list")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            return output.contains(MCP_SERVER_NAME + ":");
        } catch (Exception e) {
            return false;
        }
    }

    private static String resolveJarPath() {
        String jarPath = NamecheapAuth.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath();
        if (jarPath.endsWith(".jar")) {
            return Path.of(jarPath).toAbsolutePath().toString();
        }
        return Path.of("target", "namecheap-mcp-1.0.0.jar").toAbsolutePath().toString();
    }

    private static void printManualRegistration() {
        String jarPath = resolveJarPath();
        System.err.println("  claude mcp add --scope user --transport stdio namecheap -- \\");
        System.err.println("    java -jar " + jarPath);
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
