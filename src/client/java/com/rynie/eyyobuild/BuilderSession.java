package com.rynie.eyyobuild;

import com.rynie.eyyobuild.schematic.SchematicSummary;
import net.minecraft.client.Minecraft;

/** Public entry point for the visible, single-player schematic builder session. */
public final class BuilderSession {
    private BuilderSession() {}

    public static synchronized void toggle(Minecraft client, SchematicSummary schematic) {
        if (BuildEngine.isActive()) {
            BuildEngine.stop("İnşaat durduruldu.");
        } else {
            BuildEngine.start(client, schematic);
        }
    }

    public static synchronized boolean isActive() {
        return BuildEngine.isActive();
    }

    public static synchronized void stop() {
        BuildEngine.stop("İnşaat durduruldu.");
    }

    public static synchronized void tick(Minecraft client) {
        BuildEngine.tick(client);
    }

    public static synchronized String status() {
        return BuildEngine.status();
    }
}
