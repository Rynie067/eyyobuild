package com.rynie.eyyobuild;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
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
    private static final Set<String> AUTH_ARGUMENTS = Set.of(
            "--username", "--uuid", "--accessToken", "--userType",
            "--userProperties", "--clientId", "--xuid", "--gameDir"
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

            // Keep the same mod set. Hard links are used for JARs when the
            // profile and secondary folder are on the same filesystem.
            synchronizeDirectory(sourceMods, secondaryGameDir.resolve("mods"), true, true, true);

            Path sourceConfig = FabricLoader.getInstance().getConfigDir();
            if (Files.isDirectory(sourceConfig)) {
                // Copy config only on first launch so the secondary instance
                // can keep its own options without fighting the first client.
                synchronizeDirectory(sourceConfig, secondaryGameDir.resolve("config"), false, false, false);
            }

            List<String> command = createCommand(nickname, secondaryGameDir);
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

            return new LaunchResult(true,
                    "İkinci Minecraft başlatıldı: " + nickname + "  |  PID " + pid
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

    private static List<String> createCommand(String nickname, Path secondaryGameDir) throws IOException {
        String mainCommand = System.getProperty("sun.java.command", "");
        List<String> mainTokens = splitCommandLine(mainCommand);
        if (mainTokens.isEmpty()) {
            throw new IOException("Çalışan Minecraft'ın başlatma komutu okunamadı.");
        }

        String mainClass = mainTokens.remove(0);
        if (!mainClass.contains("KnotClient")) {
            throw new IOException("Bu başlatıcı Fabric KnotClient kullanmıyor; ikinci istemci güvenli şekilde çoğaltılamadı.");
        }

        String classPath = System.getProperty("java.class.path", "");
        if (classPath.isBlank()) {
            throw new IOException("Java classpath bilgisi boş; ikinci istemci oluşturulamadı.");
        }

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                windows ? "javaw.exe" : "java").toAbsolutePath();
        if (!Files.isRegularFile(javaExecutable) && windows) {
            javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toAbsolutePath();
        }
        if (!Files.isRegularFile(javaExecutable)) {
            throw new IOException("Java çalıştırılabilir dosyası bulunamadı: " + javaExecutable);
        }

        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());

        // Reuse the exact JVM/module/native flags from this client, but give
        // the extra client a bounded heap so two instances do not both reserve
        // an unnecessarily large amount of memory.
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argument.startsWith("-Xms") || argument.startsWith("-Xmx")
                    || argument.startsWith("-XX:ErrorFile=")
                    || argument.startsWith("-XX:HeapDumpPath=")) {
                continue;
            }
            command.add(argument);
        }

        long currentHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        long secondaryHeapMb = Math.max(1024L, Math.min(3072L, currentHeapMb));
        command.add("-Xms512M");
        command.add("-Xmx" + secondaryHeapMb + "M");
        command.add("-XX:ErrorFile=" + secondaryGameDir.resolve("hs_err_pid%p.log"));
        command.add("-XX:HeapDumpPath=" + secondaryGameDir.resolve("heapdump.hprof"));
        command.add("-cp");
        command.add(classPath);
        command.add(mainClass);

        List<String> gameArguments = new ArrayList<>();
        for (int i = 0; i < mainTokens.size(); i++) {
            String argument = mainTokens.get(i);
            if (AUTH_ARGUMENTS.contains(argument) || CONNECTION_ARGUMENTS.contains(argument)) {
                if (i + 1 < mainTokens.size() && !mainTokens.get(i + 1).startsWith("--")) i++;
                continue;
            }

            gameArguments.add(argument);
            if (i + 1 < mainTokens.size() && !mainTokens.get(i + 1).startsWith("--")) {
                gameArguments.add(mainTokens.get(++i));
            }
        }
        command.addAll(gameArguments);

        UUID offlineUuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + nickname).getBytes(StandardCharsets.UTF_8));
        command.add("--username");
        command.add(nickname);
        command.add("--uuid");
        command.add(offlineUuid.toString().replace("-", ""));
        command.add("--accessToken");
        command.add("0");
        command.add("--userType");
        command.add("legacy");
        command.add("--userProperties");
        command.add("{}");
        command.add("--gameDir");
        command.add(secondaryGameDir.toAbsolutePath().toString());

        return command;
    }

    /**
     * Parses the main-class/argument string reported by the JVM. Quoted paths
     * are kept as single arguments, including Windows paths containing spaces.
     */
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
