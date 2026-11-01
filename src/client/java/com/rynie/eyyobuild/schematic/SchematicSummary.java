package com.rynie.eyyobuild.schematic;

import java.nio.file.Path;
import java.util.List;

public record SchematicSummary(
        Path path,
        String displayName,
        int width,
        int height,
        int length,
        long totalBlocks,
        List<RegionSummary> regions,
        List<SchematicBlock> blocks,
        String error
) {
    public SchematicSummary {
        regions = List.copyOf(regions);
        blocks = List.copyOf(blocks);
    }

    public boolean readable() {
        return error == null || error.isBlank();
    }

    public static SchematicSummary failed(Path path, String message) {
        return new SchematicSummary(
                path,
                path.getFileName() == null ? path.toString() : path.getFileName().toString(),
                0, 0, 0, 0L, List.of(), List.of(), message
        );
    }

    public record RegionSummary(
            String name,
            int width,
            int height,
            int length,
            int paletteEntries,
            long volume
    ) {
    }
}
