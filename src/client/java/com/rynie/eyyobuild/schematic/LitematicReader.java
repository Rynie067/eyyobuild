package com.rynie.eyyobuild.schematic;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reader for Litematica's gzip-compressed NBT format.
 *
 * Reads the real Position/Size compounds, palettes, properties and packed BlockStates.
 * Litematica packs palette indexes as a continuous little-endian bit stream; values can
 * straddle two longs. The linear order is X fastest, then Z, then Y.
 */
public final class LitematicReader {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_ARRAY_LENGTH = 16_000_000;
    private static final long MAX_DECODED_BLOCKS = 8_000_000L;

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
            readString(input);
            root = asCompound(readPayload(input, rootType, 0));
        }

        Map<String, Object> metadata = asCompound(root.get("Metadata"));
        Map<String, Object> regionsTag = asCompound(root.get("Regions"));
        if (regionsTag.isEmpty()) {
            throw new IOException("The schematic has no regions.");
        }

        List<SchematicSummary.RegionSummary> regionSummaries = new ArrayList<>();
        List<AbsoluteBlock> decoded = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        long decodedCount = 0L;

        for (Map.Entry<String, Object> regionEntry : regionsTag.entrySet()) {
            Map<String, Object> region = asCompound(regionEntry.getValue());
            Map<String, Object> position = asCompound(region.get("Position"));
            Map<String, Object> size = asCompound(region.get("Size"));

            int sx = coordinate(size, "x");
            int sy = coordinate(size, "y");
            int sz = coordinate(size, "z");
            int width = safeDimension(sx);
            int height = safeDimension(sy);
            int length = safeDimension(sz);
            if (width == 0 || height == 0 || length == 0) {
                continue;
            }

            long volume = (long) width * height * length;
            if (volume > MAX_DECODED_BLOCKS || decodedCount + volume > MAX_DECODED_BLOCKS) {
                throw new IOException("Schematic is too large to decode safely (limit: "
                        + MAX_DECODED_BLOCKS + " positions).");
            }

            int px = coordinate(position, "x");
            int py = coordinate(position, "y");
            int pz = coordinate(position, "z");
            int regionMinX = px + (sx < 0 ? sx + 1 : 0);
            int regionMinY = py + (sy < 0 ? sy + 1 : 0);
            int regionMinZ = pz + (sz < 0 ? sz + 1 : 0);

            List<?> paletteTag = asList(region.get("BlockStatePalette"));
            if (paletteTag.isEmpty()) {
                throw new IOException("Region '" + regionEntry.getKey() + "' has an empty block palette.");
            }
            List<BlockPaletteEntry> palette = new ArrayList<>(paletteTag.size());
            for (Object paletteValue : paletteTag) {
                Map<String, Object> state = asCompound(paletteValue);
                String blockId = asString(state.get("Name"));
                if (blockId == null || blockId.isBlank()) {
                    throw new IOException("Region '" + regionEntry.getKey() + "' contains a palette entry without Name.");
                }
                Map<String, String> properties = new LinkedHashMap<>();
                Map<String, Object> propertyTag = asCompound(state.get("Properties"));
                for (Map.Entry<String, Object> property : propertyTag.entrySet()) {
                    String value = asString(property.getValue());
                    if (value != null) {
                        properties.put(property.getKey(), value);
                    }
                }
                palette.add(new BlockPaletteEntry(blockId, properties));
            }

            long[] packedStates = asLongArray(region.get("BlockStates"));
            if (packedStates.length == 0 && volume > 1) {
                throw new IOException("Region '" + regionEntry.getKey() + "' has no BlockStates data.");
            }

            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            long mask = (1L << bits) - 1L;

            for (int index = 0; index < (int) volume; index++) {
                int paletteIndex = unpack(packedStates, index, bits, mask);
                if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                    throw new IOException("Region '" + regionEntry.getKey()
                            + "' has an invalid palette index at block " + index + ".");
                }

                BlockPaletteEntry entry = palette.get(paletteIndex);
                if (isAir(entry.blockId())) {
                    continue;
                }

                int localX = index % width;
                int localZ = (index / width) % length;
                int localY = index / (width * length);
                int absoluteX = regionMinX + localX;
                int absoluteY = regionMinY + localY;
                int absoluteZ = regionMinZ + localZ;

                decoded.add(new AbsoluteBlock(absoluteX, absoluteY, absoluteZ, entry.blockId(), entry.properties()));
                minX = Math.min(minX, absoluteX);
                minY = Math.min(minY, absoluteY);
                minZ = Math.min(minZ, absoluteZ);
                maxX = Math.max(maxX, absoluteX);
                maxY = Math.max(maxY, absoluteY);
                maxZ = Math.max(maxZ, absoluteZ);
            }

            decodedCount += volume;
            regionSummaries.add(new SchematicSummary.RegionSummary(
                    regionEntry.getKey(), width, height, length, palette.size(), volume
            ));
        }

        if (regionSummaries.isEmpty()) {
            throw new IOException("No valid non-empty regions were found.");
        }

        List<SchematicBlock> blocks = new ArrayList<>(decoded.size());
        if (!decoded.isEmpty()) {
            for (AbsoluteBlock block : decoded) {
                // Keep Litematica's coordinates relative to the placement origin.
                // Normalizing to the schematic's minimum corner shifts negative-offset regions.
                blocks.add(new SchematicBlock(
                        block.x(),
                        block.y(),
                        block.z(),
                        block.blockId(),
                        block.properties()
                ));
            }
        }
        blocks.sort(Comparator.comparingInt(SchematicBlock::y)
                .thenComparingInt(SchematicBlock::z)
                .thenComparingInt(SchematicBlock::x));

        String name = asString(metadata.get("Name"));
        if (name == null || name.isBlank()) {
            name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        }

        int overallWidth = decoded.isEmpty() ? 0 : maxX - minX + 1;
        int overallHeight = decoded.isEmpty() ? 0 : maxY - minY + 1;
        int overallLength = decoded.isEmpty() ? 0 : maxZ - minZ + 1;
        Map<String, Object> enclosingSize = asCompound(metadata.get("EnclosingSize"));
        if (!enclosingSize.isEmpty()) {
            overallWidth = Math.max(overallWidth, safeDimension(coordinate(enclosingSize, "x")));
            overallHeight = Math.max(overallHeight, safeDimension(coordinate(enclosingSize, "y")));
            overallLength = Math.max(overallLength, safeDimension(coordinate(enclosingSize, "z")));
        }

        long totalBlocks = asLong(metadata.get("TotalBlocks"), blocks.size());
        return new SchematicSummary(
                file, name, overallWidth, overallHeight, overallLength,
                totalBlocks, regionSummaries, blocks, null
        );
    }

    private static int unpack(long[] data, int index, int bits, long mask) throws IOException {
        if (data.length == 0) {
            if (index == 0) return 0;
            throw new IOException("Missing packed block-state data.");
        }
        long bitIndex = (long) index * bits;
        int wordIndex = (int) (bitIndex >>> 6);
        int bitOffset = (int) (bitIndex & 63);
        if (wordIndex >= data.length) {
            throw new IOException("Packed block-state data ended unexpectedly.");
        }
        long value = data[wordIndex] >>> bitOffset;
        if (bitOffset + bits > 64) {
            if (wordIndex + 1 >= data.length) {
                throw new IOException("Packed block-state entry crosses a missing long.");
            }
            value |= data[wordIndex + 1] << (64 - bitOffset);
        }
        return (int) (value & mask);
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
                    if (childType == 0) break;
                    String name = readString(input);
                    values.put(name, readPayload(input, childType, depth + 1));
                }
                yield values;
            }
            case 11 -> {
                int length = readLength(input);
                int[] values = new int[length];
                for (int i = 0; i < length; i++) values[i] = input.readInt();
                yield values;
            }
            case 12 -> {
                int length = readLength(input);
                long[] values = new long[length];
                for (int i = 0; i < length; i++) values[i] = input.readLong();
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
        if (value == null) return Map.of();
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IOException("Expected NBT compound, got " + value.getClass().getSimpleName());
    }

    private static List<?> asList(Object value) throws IOException {
        if (value == null) return List.of();
        if (value instanceof List<?> list) return list;
        throw new IOException("Expected NBT list.");
    }

    private static long[] asLongArray(Object value) throws IOException {
        if (value == null) return new long[0];
        if (value instanceof long[] values) return values;
        throw new IOException("Expected NBT long array.");
    }

    private static String asString(Object value) {
        return value instanceof String text ? text : null;
    }

    private static int coordinate(Map<String, Object> compound, String key) throws IOException {
        Object value = compound.get(key);
        if (value instanceof Number number) return number.intValue();
        throw new IOException("Missing numeric coordinate '" + key + "'.");
    }

    private static long asLong(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static int safeDimension(int value) {
        return (int) Math.min(Math.abs((long) value), Integer.MAX_VALUE);
    }

    private static boolean isAir(String blockId) {
        return blockId.equals("minecraft:air")
                || blockId.equals("minecraft:cave_air")
                || blockId.equals("minecraft:void_air");
    }

    private record BlockPaletteEntry(String blockId, Map<String, String> properties) {}
    private record AbsoluteBlock(int x, int y, int z, String blockId, Map<String, String> properties) {}
}
