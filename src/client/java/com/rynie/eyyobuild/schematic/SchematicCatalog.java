package com.rynie.eyyobuild.schematic;

import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Catalog of schematic files currently referenced by enabled Litematica placements.
 * It deliberately does not scan the whole schematics directory: disabled/closed
 * placements are excluded from the catalog.
 */
public final class SchematicCatalog {
    private static final List<SchematicSummary> SCHEMATICS = new ArrayList<>();
    private static final Map<Path, CachedSummary> CACHE = new LinkedHashMap<>();
    private static Path schematicDirectory;
    private static String lastError;
    private static int selectedIndex;

    private SchematicCatalog() {}

    public static synchronized void refresh(Path gameDirectory) {
        schematicDirectory = gameDirectory.resolve("schematics");
        lastError = null;

        Path selectedPath = current() == null ? null : normalize(current().path());
        List<Path> activeFiles;
        try {
            activeFiles = findEnabledPlacementFiles();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            SCHEMATICS.clear();
            selectedIndex = 0;
            CACHE.clear();
            lastError = "Litematica yerleşimleri okunamadı: " + exception.getClass().getSimpleName();
            return;
        }

        LinkedHashSet<Path> uniqueFiles = new LinkedHashSet<>(activeFiles);
        List<Path> orderedPaths = uniqueFiles.stream()
                .sorted(Comparator.comparing(path -> fileName(path).toLowerCase()))
                .toList();

        List<SchematicSummary> refreshed = new ArrayList<>(orderedPaths.size());
        for (Path path : orderedPaths) {
            try {
                long size = Files.isRegularFile(path) ? Files.size(path) : -1L;
                FileTime modified = Files.exists(path) ? Files.getLastModifiedTime(path) : FileTime.fromMillis(0L);
                CachedSummary cached = CACHE.get(path);
                if (cached != null && cached.size() == size && cached.modified().equals(modified)) {
                    refreshed.add(cached.summary());
                    continue;
                }

                SchematicSummary summary;
                try {
                    summary = LitematicReader.read(path);
                } catch (Exception exception) {
                    summary = SchematicSummary.failed(path, safeMessage(exception));
                }
                CACHE.put(path, new CachedSummary(size, modified, summary));
                refreshed.add(summary);
            } catch (IOException exception) {
                SchematicSummary failed = SchematicSummary.failed(path, safeMessage(exception));
                refreshed.add(failed);
                CACHE.put(path, new CachedSummary(-1L, FileTime.fromMillis(0L), failed));
            }
        }

        SCHEMATICS.clear();
        SCHEMATICS.addAll(refreshed);
        CACHE.keySet().retainAll(uniqueFiles);

        selectedIndex = 0;
        if (selectedPath != null) {
            for (int i = 0; i < SCHEMATICS.size(); i++) {
                if (normalize(SCHEMATICS.get(i).path()).equals(selectedPath)) {
                    selectedIndex = i;
                    break;
                }
            }
        }
        if (selectedIndex >= SCHEMATICS.size()) selectedIndex = 0;
    }

    private static List<Path> findEnabledPlacementFiles() throws ReflectiveOperationException {
        if (!FabricLoader.getInstance().isModLoaded("litematica")) {
            throw new IllegalStateException("Litematica yüklü değil.");
        }

        Class<?> dataManagerClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
        Object manager = dataManagerClass.getMethod("getSchematicPlacementManager").invoke(null);
        if (manager == null) {
            throw new IllegalStateException("Litematica yerleşim yöneticisi bulunamadı.");
        }

        Object value = manager.getClass().getMethod("getAllSchematicsPlacements").invoke(manager);
        if (!(value instanceof List<?> placements)) {
            throw new IllegalStateException("Litematica yerleşim listesi okunamadı.");
        }

        List<Path> files = new ArrayList<>();
        for (Object placement : placements) {
            if (placement == null) continue;
            try {
                Object enabled = placement.getClass().getMethod("isEnabled").invoke(placement);
                if (!Boolean.TRUE.equals(enabled)) continue;

                Object fileValue = placement.getClass().getMethod("getSchematicFile").invoke(placement);
                Path path = fileValue instanceof File file ? file.toPath()
                        : fileValue instanceof Path filePath ? filePath : null;
                if (path == null || !fileName(path).toLowerCase().endsWith(".litematic")) continue;
                files.add(normalize(path));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // One malformed placement must not hide other valid, enabled placements.
            }
        }
        return files;
    }

    public static synchronized void next() {
        if (!SCHEMATICS.isEmpty()) selectedIndex = (selectedIndex + 1) % SCHEMATICS.size();
    }

    public static synchronized void previous() {
        if (!SCHEMATICS.isEmpty()) selectedIndex = (selectedIndex - 1 + SCHEMATICS.size()) % SCHEMATICS.size();
    }

    public static synchronized SchematicSummary current() {
        if (SCHEMATICS.isEmpty() || selectedIndex < 0 || selectedIndex >= SCHEMATICS.size()) return null;
        return SCHEMATICS.get(selectedIndex);
    }

    public static synchronized List<SchematicSummary> all() {
        return List.copyOf(SCHEMATICS);
    }

    public static synchronized int selectedIndex() {
        return selectedIndex;
    }

    public static synchronized int size() {
        return SCHEMATICS.size();
    }

    public static synchronized Path directory() {
        return schematicDirectory;
    }

    public static synchronized String lastError() {
        return lastError;
    }

    private static Path normalize(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException ignored) {
            return absolute;
        }
    }

    private static String fileName(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private record CachedSummary(long size, FileTime modified, SchematicSummary summary) {}
}
