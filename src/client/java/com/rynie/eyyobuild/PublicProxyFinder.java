package com.rynie.eyyobuild;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Finds public SOCKS5 endpoints and verifies them with a real CONNECT handshake. */
public final class PublicProxyFinder {
    private static final List<String> SOURCES = List.of(
            "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
            "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks5.txt"
    );
    private static final int MAX_CANDIDATES = 60;
    private static final int MAX_PARALLEL_TESTS = 12;
    private static final long TEST_WINDOW_SECONDS = 25;

    private PublicProxyFinder() {}

    public static FindResult findWorkingProxy() {
        List<Endpoint> candidates;
        try {
            candidates = downloadCandidates();
        } catch (Exception exception) {
            return new FindResult(false, "", 0, "Proxy listeleri indirilemedi: " + safeMessage(exception));
        }

        if (candidates.isEmpty()) {
            return new FindResult(false, "", 0, "Listelerde test edilecek SOCKS5 proxy bulunamadı.");
        }

        ExecutorService executor = Executors.newFixedThreadPool(
                MAX_PARALLEL_TESTS, Thread.ofVirtual().name("eyyobuild-proxy-check-", 0).factory()
        );
        CompletionService<Endpoint> completed = new ExecutorCompletionService<>(executor);
        List<Future<Endpoint>> tasks = new ArrayList<>();
        int submitted = Math.min(MAX_CANDIDATES, candidates.size());

        try {
            for (int i = 0; i < submitted; i++) {
                Endpoint endpoint = candidates.get(i);
                tasks.add(completed.submit(() -> {
                    Socks5ProxyTester.TestResult test = Socks5ProxyTester.test(endpoint.host(), endpoint.port());
                    return test.success() ? endpoint : null;
                }));
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TEST_WINDOW_SECONDS);
            int finished = 0;
            while (finished < submitted && System.nanoTime() < deadline) {
                Future<Endpoint> result = completed.poll(250, TimeUnit.MILLISECONDS);
                if (result == null) continue;
                finished++;
                try {
                    Endpoint endpoint = result.get();
                    if (endpoint != null) {
                        return new FindResult(true, endpoint.host(), endpoint.port(),
                                "Çalışan SOCKS5 proxy bulundu ve tünel testi geçti.");
                    }
                } catch (ExecutionException ignored) {
                    // An unavailable public proxy does not stop the search.
                }
            }

            return new FindResult(false, "", 0,
                    "Test edilen " + finished + "/" + submitted
                            + " proxy içinde çalışan SOCKS5 bulunamadı. Tekrar deneyebilirsin.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new FindResult(false, "", 0, "Proxy araması durduruldu.");
        } finally {
            for (Future<Endpoint> task : tasks) task.cancel(true);
            executor.shutdownNow();
        }
    }

    private static List<Endpoint> downloadCandidates() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        Set<Endpoint> unique = new LinkedHashSet<>();
        for (String source : SOURCES) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                    .timeout(Duration.ofSeconds(9))
                    .header("User-Agent", "EYYOBUILD-Proxy-Finder")
                    .GET()
                    .build();

            try {
                HttpResponse<String> response = client.send(
                        request, HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() != 200 || response.body().length() > 2_000_000) continue;

                for (String rawLine : response.body().split("\\R")) {
                    Endpoint endpoint = parseEndpoint(rawLine);
                    if (endpoint != null) unique.add(endpoint);
                    if (unique.size() >= MAX_CANDIDATES * 2) break;
                }
            } catch (IOException ignored) {
                // Try the next public list source.
            }
            if (unique.size() >= MAX_CANDIDATES * 2) break;
        }
        return new ArrayList<>(unique);
    }

    private static Endpoint parseEndpoint(String rawLine) {
        if (rawLine == null) return null;
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#") || line.contains("@")) return null;
        if (line.startsWith("socks5://")) line = line.substring("socks5://".length());

        int separator = line.lastIndexOf(':');
        if (separator <= 0 || separator == line.length() - 1) return null;

        String host = line.substring(0, separator).trim();
        if (host.startsWith("[") || host.contains("/") || host.contains(" ")) return null;

        try {
            int port = Integer.parseInt(line.substring(separator + 1).trim());
            if (port < 1 || port > 65535 || !ProxySettings.isValidHost(host)) return null;
            return new Endpoint(host, port);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private record Endpoint(String host, int port) {}
    public record FindResult(boolean success, String host, int port, String message) {}
}
