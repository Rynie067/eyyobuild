package com.rynie.eyyobuild.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class SchematicCatalog {
    private static final List<SchematicSummary> SCHEMATICS = new ArrayList<>();
    private static Path schematicDirectory;
    private static String lastError;
    private static int selectedIndex;

    private SchematicCatalog() {
    }

    public static synchronized void refresh(Path gameDirectory) {
        schematicDirectory = gameDirectory.resolve("schematics");
        SCHEMATICS.clear();
        lastError = null;
        selectedIndex = 0;

        try {
            Files.createDirectories(schematicDirectory);
        } catch (IOException exception) {
            lastError = "Could not create schematics folder: " + exception.getMessage();
            return;
        }

        try (Stream<Path> files = Files.list(schematicDirectory)) {
            List<Path> paths = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".litematic"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .toList();

            for (Path path : paths) {
                try {
                    SCHEMATICS.add(LitematicReader.read(path));
                } catch (Exception exception) {
                    SCHEMATICS.add(SchematicSummary.failed(path, safeMessage(exception)));
                }
            }
        } catch (IOException exception) {
            lastError = "Could not scan schematics folder: " + exception.getMessage();
        }
    }

    public static synchronized void next() {
        if (!SCHEMATICS.isEmpty()) {
            selectedIndex = (selectedIndex + 1) % SCHEMATICS.size();
        }
    }

    public static synchronized void previous() {
        if (!SCHEMATICS.isEmpty()) {
            selectedIndex = (selectedIndex - 1 + SCHEMATICS.size()) % SCHEMATICS.size();
        }
    }

    public static synchronized SchematicSummary current() {
        if (SCHEMATICS.isEmpty() || selectedIndex < 0 || selectedIndex >= SCHEMATICS.size()) {
            return null;
        }
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

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
