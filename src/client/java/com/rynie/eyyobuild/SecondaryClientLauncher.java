package com.rynie.eyyobuild;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Starts another Fabric client from the currently running launch, without
 * creating or editing a launcher profile. The second client uses an offline
 * username and an isolated game directory with a synchronized copy of mods.
 */
public final class SecondaryClientLauncher {
    private static final Pattern VALID_NICKNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern JAVA_CLASS = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Set<String> AUTH_ARGUMENTS = Set.of(
            "--username", "--uuid", "--accessToken", "--userType",
            "--userProperties", "--clientId", "--xuid", "--gameDir",
            "--proxyHost", "--proxyPort", "--proxyUser", "--proxyPass"
    );
    private static final Set<String> CONNECTION_ARGUMENTS = Set.of(
            "--quickPlayPath", "--quickPlaySingleplayer", "--quickPlayMultiplayer",
            "--quickPlayRealms", "--server", "--port"
    );

    private SecondaryClientLauncher() {}

    public static boolean isValidNickname(String nickname) {
        return nickname != null && VALID_NICKNAME.matcher(nickname.trim()).matches();
    }

    public static LaunchResult launch(String rawNickname) {
        return launch(rawNickname, ProxySettings.snapshot());
    }

    public static LaunchResult launch(String rawNickname, ProxySettings.Snapshot proxy) {
        String nickname = rawNickname == null ? "" : rawNickname.trim();
        if (!isValidNickname(nickname)) {
            return new LaunchResult(false, "Nick 3-16 karakter olmalı; yalnızca İngilizce harf, rakam ve _ kullan.");
        }

        try {
            Path currentGameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
            Path accountsRoot = currentGameDir.resolve("eyyobuild-accounts").normalize();
            Path secondaryGameDir = accountsRoot.resolve(nickname).normalize();
            if (!secondaryGameDir.startsWith(accountsRoot)) {
                return new LaunchResult(false, "Geçersiz hesap klasörü.");
            }

            Files.createDirectories(secondaryGameDir);
            Path pidFile = secondaryGameDir.resolve("eyyobuild-instance.pid");
            if (isProcessRunning(pidFile)) {
                return new LaunchResult(false, nickname + " hesabıyla ikinci oyun zaten açık.");
            }

            Path sourceMods = currentGameDir.resolve("mods");
            if (!Files.isDirectory(sourceMods)) {
                return new LaunchResult(false, "Mevcut profilin mods klasörü bulunamadı: " + sourceMods);
            }

            synchronizeDirectory(sourceMods, secondaryGameDir.resolve("mods"), true, true, true);

            Path sourceConfig = FabricLoader.getInstance().getConfigDir();
            if (Files.isDirectory(sourceConfig)) {
                synchronizeDirectory(sourceConfig, secondaryGameDir.resolve("config"), false, false, false);
            }

            List<String> command = createCommand(nickname, secondaryGameDir, proxy);
            Path logFile = secondaryGameDir.resolve("logs").resolve("eyyobuild-secondary.log");
            Files.createDirectories(logFile.getParent());

            Process process = new ProcessBuilder(command)
                    .directory(Path.of(System.getProperty("user.dir")).toAbsolutePath().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();

            long pid = process.pid();
            Files.writeString(pidFile, Long.toString(pid), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            process.onExit().thenRun(() -> {
                try {
                    if (Files.exists(pidFile) && Files.readString(pidFile).trim().equals(Long.toString(pid))) {
                        Files.deleteIfExists(pidFile);
                    }
                } catch (IOException ignored) {
                    // A stale PID file is checked against ProcessHandle next time.
                }
            });

            String proxyLabel = proxy != null && proxy.enabled()
                    ? " | SOCKS5 " + proxy.host() + ":" + proxy.port()
                    : " | Proxy: yok";
            return new LaunchResult(true,
                    "İkinci Minecraft başlatıldı: " + nickname + proxyLabel
                            + "  |  PID " + pid
                            + "  |  Günlük: eyyobuild-accounts/" + nickname + "/logs/");
        } catch (Exception exception) {
            String detail = exception.getMessage();
            if (detail == null || detail.isBlank()) detail = exception.getClass().getSimpleName();
            return new LaunchResult(false, "İkinci oyun başlatılamadı: " + detail);
        }
    }

    private static boolean isProcessRunning(Path pidFile) {
        if (!Files.isRegularFile(pidFile)) return false;
        try {
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (IOException | NumberFormatException exception) {
            return false;
        }
    }

    private static List<String> createCommand(
            String nickname, Path secondaryGameDir, ProxySettings.Snapshot proxy
    ) throws IOException {
        ProcessHandle.Info processInfo = ProcessHandle.current().info();
        Path javaExecutable = processInfo.command()
                .map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin",
                        isWindows() ? "javaw.exe" : "java"));
        javaExecutable = javaExecutable.toAbsolutePath();
        if (!Files.isRegularFile(javaExecutable) && isWindows()) {
            Path consoleJava = Path.of(System.getProperty("java.home"), "bin", "java.exe").toAbsolutePath();
            if (Files.isRegularFile(consoleJava)) javaExecutable = consoleJava;
        }
        if (!Files.isRegularFile(javaExecutable)) {
            throw new IOException("Java çalıştırılabilir dosyası bulunamadı: " + javaExecutable);
        }

        List<String> originalArguments = new ArrayList<>(Arrays.asList(
                processInfo.arguments().orElse(new String[0])
        ));
        originalArguments = expandArgumentFiles(originalArguments);

        List<String> reportedCommand = splitCommandLine(System.getProperty("sun.java.command", ""));
        int entryIndex = -1;
        int entryLength = 1;

        // Preserve jar/module launch modes when the launcher uses them.
        for (int i = 0; i < originalArguments.size(); i++) {
            String token = originalArguments.get(i);
            if ((token.equals("-jar") || token.equals("-m") || token.equals("--module"))
                    && i + 1 < originalArguments.size()) {
                entryIndex = i;
                entryLength = 2;
                break;
            }
        }

        if (entryIndex < 0 && !reportedCommand.isEmpty()) {
            entryIndex = originalArguments.indexOf(reportedCommand.get(0));
        }
        if (entryIndex < 0) {
            for (int i = 0; i < originalArguments.size(); i++) {
                if (originalArguments.get(i).contains("KnotClient")) {
                    entryIndex = i;
                    break;
                }
            }
        }
        if (entryIndex < 0) {
            for (int i = 0; i < originalArguments.size(); i++) {
                if (JAVA_CLASS.matcher(originalArguments.get(i)).matches()) {
                    entryIndex = i;
                    break;
                }
            }
        }

        List<String> jvmArguments;
        List<String> entryPoint;
        List<String> gameArguments;

        if (entryIndex >= 0) {
            jvmArguments = new ArrayList<>(originalArguments.subList(0, entryIndex));
            entryPoint = new ArrayList<>(originalArguments.subList(entryIndex, entryIndex + entryLength));
            gameArguments = new ArrayList<>(originalArguments.subList(entryIndex + entryLength,
                    originalArguments.size()));
        } else {
            // Fallback for JVM builds that hide ProcessHandle argument details.
            List<String> commandTokens = splitCommandLine(System.getProperty("sun.java.command", ""));
            String classPath = System.getProperty("java.class.path", "");
            if (commandTokens.isEmpty() || classPath.isBlank()) {
                throw new IOException("Launcher'ın Java komut satırı okunamadı; ikinci istemci güvenli şekilde başlatılamadı.");
            }
            jvmArguments = new ArrayList<>(List.of("-cp", classPath));
            entryPoint = List.of(commandTokens.remove(0));
            gameArguments = commandTokens;
        }

        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        for (String argument : jvmArguments) {
            if (!isReplaceableJvmArgument(argument)) command.add(argument);
        }

        long currentHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        long secondaryHeapMb = Math.max(1024L, Math.min(3072L, currentHeapMb));
        command.add("-Xms512M");
        command.add("-Xmx" + secondaryHeapMb + "M");
        command.add("-XX:ErrorFile=" + secondaryGameDir.resolve("hs_err_pid%p.log"));
        command.add("-XX:HeapDumpPath=" + secondaryGameDir.resolve("heapdump.hprof"));
        command.addAll(entryPoint);
        command.addAll(filterGameArguments(gameArguments));

        UUID offlineUuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + nickname).getBytes(StandardCharsets.UTF_8));
        command.add("--username");
        command.add(nickname);
        command.add("--uuid");
        command.add(offlineUuid.toString().replace("-", ""));
        command.add("--accessToken");
        command.add("0");
        command.add("--gameDir");
        command.add(secondaryGameDir.toAbsolutePath().toString());

        if (proxy != null && proxy.enabled()) {
            command.add("--proxyHost");
            command.add(proxy.host());
            command.add("--proxyPort");
            command.add(Integer.toString(proxy.port()));
        }

        return command;
    }

    private static List<String> expandArgumentFiles(List<String> arguments) {
        List<String> expanded = new ArrayList<>();
        for (String argument : arguments) {
            if (argument.startsWith("@") && argument.length() > 1) {
                Path file = Path.of(argument.substring(1));
                if (Files.isRegularFile(file)) {
                    try {
                        expanded.addAll(splitCommandLine(Files.readString(file)));
                        continue;
                    } catch (IOException ignored) {
                        // The fallback below reports if the launch command cannot be reconstructed.
                    }
                }
            }
            expanded.add(argument);
        }
        return expanded;
    }

    private static List<String> filterGameArguments(List<String> arguments) {
        List<String> filtered = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            int equals = argument.indexOf('=');
            String option = equals >= 0 ? argument.substring(0, equals) : argument;
            if (AUTH_ARGUMENTS.contains(option) || CONNECTION_ARGUMENTS.contains(option)) {
                if (equals < 0 && i + 1 < arguments.size() && !arguments.get(i + 1).startsWith("--")) {
                    i++;
                }
                continue;
            }
            filtered.add(argument);
        }
        return filtered;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isReplaceableJvmArgument(String argument) {
        if (argument.startsWith("-Xms") || argument.startsWith("-Xmx")
                || argument.startsWith("-XX:ErrorFile=")
                || argument.startsWith("-XX:HeapDumpPath=")) {
            return true;
        }
        return isProxyJvmArgument(argument);
    }

    private static boolean isProxyJvmArgument(String argument) {
        String lower = argument.toLowerCase(Locale.ROOT);
        return lower.startsWith("-dsocksproxyhost=")
                || lower.startsWith("-dsocksproxyport=")
                || lower.startsWith("-dhttp.proxyhost=")
                || lower.startsWith("-dhttp.proxyport=")
                || lower.startsWith("-dhttps.proxyhost=")
                || lower.startsWith("-dhttps.proxyport=");
    }

    private static List<String> splitCommandLine(String commandLine) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < commandLine.length(); i++) {
            char character = commandLine.charAt(i);
            if (character == '\\' && i + 1 < commandLine.length()
                    && commandLine.charAt(i + 1) == '"') {
                current.append('"');
                i++;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(character) && !quoted) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }

        if (!current.isEmpty()) tokens.add(current.toString());
        return tokens;
    }

    private static void synchronizeDirectory(
            Path source, Path target, boolean hardLinkJars, boolean updateExisting, boolean deleteMissing
    ) throws IOException {
        Files.createDirectories(target);
        Set<Path> expected = new HashSet<>();

        try (Stream<Path> paths = Files.walk(source)) {
            for (Path sourcePath : paths.toList()) {
                Path relative = source.relativize(sourcePath);
                if (relative.toString().isEmpty()) continue;
                expected.add(relative);
                Path targetPath = target.resolve(relative);

                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                    continue;
                }
                if (!Files.isRegularFile(sourcePath)) continue;

                boolean targetExists = Files.isRegularFile(targetPath);
                if (targetExists && !updateExisting) continue;
                if (targetExists
                        && Files.size(sourcePath) == Files.size(targetPath)
                        && Files.getLastModifiedTime(sourcePath).equals(Files.getLastModifiedTime(targetPath))) {
                    continue;
                }

                Files.createDirectories(targetPath.getParent());
                Files.deleteIfExists(targetPath);
                boolean isJar = sourcePath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
                if (hardLinkJars && isJar) {
                    try {
                        Files.createLink(targetPath, sourcePath);
                    } catch (IOException | UnsupportedOperationException | SecurityException linkFailure) {
                        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } else {
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }

        if (deleteMissing) {
            try (Stream<Path> paths = Files.walk(target)) {
                List<Path> stalePaths = paths
                        .filter(path -> !path.equals(target))
                        .sorted(Comparator.reverseOrder())
                        .toList();
                for (Path stalePath : stalePaths) {
                    if (!expected.contains(target.relativize(stalePath))) {
                        Files.deleteIfExists(stalePath);
                    }
                }
            }
        }
    }

    public record LaunchResult(boolean success, String message) {}
}
