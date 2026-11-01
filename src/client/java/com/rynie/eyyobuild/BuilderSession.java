package com.rynie.eyyobuild;

import com.rynie.eyyobuild.schematic.SchematicSummary;
import net.minecraft.client.Minecraft;

/** EYYOBUILD session facade backed by Baritone's full builder/pathing process. */
public final class BuilderSession {
    private BuilderSession() {}

    public static synchronized void toggle(Minecraft client, SchematicSummary schematic) {
        if (isActive()) {
            stop();
        } else {
            start(client, schematic);
        }
    }

    public static synchronized void start(Minecraft client, SchematicSummary schematic) {
        if (isActive()) return;
        BaritoneBuildBridge.start(client, schematic);
    }

    public static synchronized boolean isActive() {
        return BaritoneBuildBridge.isActive();
    }

    public static synchronized void stop() {
        BaritoneBuildBridge.stop("İnşaat durduruldu.");
    }

    public static synchronized void tick(Minecraft client) {
        BaritoneBuildBridge.tick(client);
    }

    public static synchronized String status() {
        return BaritoneBuildBridge.status();
    }
}
