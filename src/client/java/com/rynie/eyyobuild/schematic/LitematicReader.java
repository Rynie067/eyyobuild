package com.rynie.eyyobuild.schematic;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Small, defensive reader for the compressed NBT metadata used by Litematica files.
 *
 * This first pass reads region dimensions and palette sizes. Block-state long arrays
 * are parsed so the NBT stream remains valid, but placement decoding is intentionally
 * left for a later feature.
 */
public final class LitematicReader {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_ARRAY_LENGTH = 16_000_000;

    private LitematicReader() {
    }

    public static SchematicSummary read(Path file) throws IOException {
        Map<String, Object> root;

        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            int rootType = input.readUnsignedByte();
            if (rootType != 10) {
                throw new IOException("Root NBT tag is not a compound.");
            }

            readString(input); // Root name
            root = asCompound(readPayload(input, rootType, 0));
        }

        Map<String, Object> metadata = asCompound(root.get("Metadata"));
        Map<String, Object> regionsTag = asCompound(root.get("Regions"));
        List<SchematicSummary.RegionSummary> regions = new ArrayList<>();

        int maxWidth = 0;
        int maxHeight = 0;
        int maxLength = 0;
        long estimatedVolume = 0L;

        for (Map.Entry<String, Object> entry : regionsTag.entrySet()) {
            Map<String, Object> region = asCompound(entry.getValue());
            int[] size = asIntArray(region.get("Size"));

            if (size.length < 3) {
                continue;
            }

            int width = safeDimension(size[0]);
            int height = safeDimension(size[1]);
            int length = safeDimension(size[2]);

            Object paletteValue = region.get("BlockStatePalette");
            int paletteEntries = paletteValue instanceof List<?> list ? list.size() : 0;

            long volume = (long) width * height * length;
            estimatedVolume = saturatingAdd(estimatedVolume, volume);
            maxWidth = Math.max(maxWidth, width);
            maxHeight = Math.max(maxHeight, height);
            maxLength = Math.max(maxLength, length);

            regions.add(new SchematicSummary.RegionSummary(
                    entry.getKey(),
                    width,
                    height,
                    length,
                    paletteEntries,
                    volume
            ));
        }

        int[] enclosingSize = asIntArray(metadata.get("EnclosingSize"));
        if (enclosingSize.length >= 3) {
            maxWidth = safeDimension(enclosingSize[0]);
            maxHeight = safeDimension(enclosingSize[1]);
            maxLength = safeDimension(enclosingSize[2]);
        }

        String name = asString(metadata.get("Name"));
        if (name == null || name.isBlank()) {
            name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        }

        long totalBlocks = asLong(metadata.get("TotalBlocks"), estimatedVolume);

        return new SchematicSummary(
                file,
                name,
                maxWidth,
                maxHeight,
                maxLength,
                totalBlocks,
                regions,
                null
        );
    }

    private static Object readPayload(DataInputStream input, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nesting limit exceeded.");
        }

        return switch (type) {
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3 -> input.readInt();
            case 4 -> input.readLong();
            case 5 -> input.readFloat();
            case 6 -> input.readDouble();
            case 7 -> {
                int length = readLength(input);
                byte[] values = new byte[length];
                input.readFully(values);
                yield values;
            }
            case 8 -> readString(input);
            case 9 -> {
                int childType = input.readUnsignedByte();
                int length = readLength(input);
                List<Object> values = new ArrayList<>(Math.min(length, 100_000));
                for (int i = 0; i < length; i++) {
                    values.add(readPayload(input, childType, depth + 1));
                }
                yield values;
            }
            case 10 -> {
                Map<String, Object> values = new LinkedHashMap<>();
                while (true) {
                    int childType = input.readUnsignedByte();
                    if (childType == 0) {
                        break;
                    }
                    String name = readString(input);
                    values.put(name, readPayload(input, childType, depth + 1));
                }
                yield values;
            }
            case 11 -> {
                int length = readLength(input);
                int[] values = new int[length];
                for (int i = 0; i < length; i++) {
                    values[i] = input.readInt();
                }
                yield values;
            }
            case 12 -> {
                int length = readLength(input);
                long[] values = new long[length];
                for (int i = 0; i < length; i++) {
                    values[i] = input.readLong();
                }
                yield values;
            }
            default -> throw new IOException("Unsupported NBT tag type: " + type);
        };
    }

    private static int readLength(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_ARRAY_LENGTH) {
            throw new IOException("Invalid or oversized NBT collection length: " + length);
        }
        return length;
    }

    private static String readString(DataInputStream input) throws IOException {
        return input.readUTF();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asCompound(Object value) throws IOException {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IOException("Expected NBT compound, got " + value.getClass().getSimpleName());
    }

    private static int[] asIntArray(Object value) {
        return value instanceof int[] values ? values : new int[0];
    }

    private static String asString(Object value) {
        return value instanceof String text ? text : null;
    }

    private static long asLong(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static int safeDimension(int value) {
        long absolute = Math.abs((long) value);
        return (int) Math.min(absolute, Integer.MAX_VALUE);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
