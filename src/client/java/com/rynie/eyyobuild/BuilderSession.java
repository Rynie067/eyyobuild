package com.rynie.eyyobuild;

import com.rynie.eyyobuild.schematic.SchematicSummary;
import net.minecraft.client.Minecraft;

/** Public entry point for the visible schematic builder session. */
public final class BuilderSession {
    private BuilderSession() {}

    public static synchronized void toggle(Minecraft client, SchematicSummary schematic) {
        if (BuildEngine.isActive()) {
            BuildEngine.stop("İnşaat durduruldu.");
        } else {
            start(client, schematic);
        }
    }

    /** Starts explicitly; used by the UI so the button never toggles an active session off. */
    public static synchronized void start(Minecraft client, SchematicSummary schematic) {
        if (BuildEngine.isActive()) return;
        BuildEngine.start(client, schematic);
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
