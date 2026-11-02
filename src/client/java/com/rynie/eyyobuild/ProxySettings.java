package com.rynie.eyyobuild;

import java.util.regex.Pattern;

/**
 * Temporary proxy settings for the next secondary Minecraft launch.
 * Values intentionally remain in memory only and are never written to disk.
 */
public final class ProxySettings {
    private static final Pattern VALID_HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.\\-]{0,251}[A-Za-z0-9]|[A-Za-z0-9]");
    private static String host = "";
    private static int port = 1080;
    private static boolean enabled;

    private ProxySettings() {}

    public static synchronized Snapshot snapshot() {
        return new Snapshot(enabled, host, port);
    }

    public static boolean isValidHost(String rawHost) {
        return rawHost != null && VALID_HOST.matcher(rawHost.trim()).matches();
    }

    public static synchronized boolean apply(String rawHost, String rawPort) {
        String normalizedHost = rawHost == null ? "" : rawHost.trim();
        String normalizedPort = rawPort == null ? "" : rawPort.trim();
        if (!isValidHost(normalizedHost)) return false;

        try {
            int parsedPort = Integer.parseInt(normalizedPort);
            if (parsedPort < 1 || parsedPort > 65535) return false;
            host = normalizedHost;
            port = parsedPort;
            enabled = true;
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    public static synchronized void clear() {
        enabled = false;
        host = "";
        port = 1080;
    }

    public record Snapshot(boolean enabled, String host, int port) {}
}
