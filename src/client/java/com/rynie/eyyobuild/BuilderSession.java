package com.rynie.eyyobuild;

import com.rynie.eyyobuild.schematic.SchematicSummary;

/**
 * State holder for the visible, manual build-assistance session.
 * This alpha does not automate player input or place blocks.
 */
public final class BuilderSession {
    private static boolean active;

    private BuilderSession() {
    }

    public static synchronized String toggle(SchematicSummary schematic) {
        if (active) {
            active = false;
            return "EYYOBUILD yardim oturumu duraklatildi.";
        }
        if (schematic == null || !schematic.readable()) {
            return "Once gecerli bir Litematica sematigi sec.";
        }
        active = true;
        return "EYYOBUILD yardim oturumu acildi. Bu surum bloklari otomatik yerlestirmez.";
    }

    public static synchronized boolean isActive() {
        return active;
    }

    public static synchronized void stop() {
        active = false;
    }
}
