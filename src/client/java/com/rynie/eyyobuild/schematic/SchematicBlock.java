package com.rynie.eyyobuild.schematic;

import java.util.Map;

/** A decoded non-air block from a Litematica schematic, in normalized schematic coordinates. */
public record SchematicBlock(int x, int y, int z, String blockId, Map<String, String> properties) {
    public SchematicBlock {
        properties = Map.copyOf(properties);
    }
}
