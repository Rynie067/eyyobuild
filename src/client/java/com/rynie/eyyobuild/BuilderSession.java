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

    public static synchronized void toggle(SchematicSummary schematic) {
        if (active) {
            active = false;
            return;
        }
        if (schematic != null && schematic.readable()) {
            active = true;
        }
    }

    public static synchronized boolean isActive() {
        return active;
    }

    public static synchronized void stop() {
        active = false;
    }
}
