package com.rynie.eyyobuild;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persistent client-side build speed setting.
 * Speed level 25 is fastest (one game tick between actions); level 1 is slowest.
 */
public final class BuildSettings {
    private static final int MIN_SPEED_LEVEL = 1;
    private static final int MAX_SPEED_LEVEL = 25;
    private static final int DEFAULT_SPEED_LEVEL = 25;

    private static int speedLevel = DEFAULT_SPEED_LEVEL;
    private static Path configFile;
    private static boolean loaded;

    private BuildSettings() {}

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        configFile = FabricLoader.getInstance().getConfigDir().resolve("eyyobuild.properties");
        if (!Files.isRegularFile(configFile)) return;

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(configFile)) {
            properties.load(input);
            String savedLevel = properties.getProperty("speedLevel");
            if (savedLevel != null) {
                speedLevel = clamp(Integer.parseInt(savedLevel.trim()), MIN_SPEED_LEVEL, MAX_SPEED_LEVEL);
                return;
            }

            // Migrate settings from 0.12/0.13 alpha builds. Their default was 25
            // delay ticks, so preserve the new max-speed default for that legacy value.
            String legacyDelay = properties.getProperty("placementDelayTicks");
            if (legacyDelay != null) {
                int oldDelay = Integer.parseInt(legacyDelay.trim());
                if (oldDelay != 25) {
                    speedLevel = clamp(26 - oldDelay, MIN_SPEED_LEVEL, MAX_SPEED_LEVEL);
                }
            }
        } catch (IOException | NumberFormatException exception) {
            System.err.println("[EYYOBUILD] Ayarlar okunamadı; varsayılan hız kullanılacak: "
                    + exception.getMessage());
            speedLevel = DEFAULT_SPEED_LEVEL;
        }
    }

    /** Baritone's click/block-break delay, in game ticks. */
    public static synchronized int getActionDelayTicks() {
        return 26 - speedLevel;
    }

    public static synchronized double getSliderValue() {
        return (double) (speedLevel - MIN_SPEED_LEVEL) / (MAX_SPEED_LEVEL - MIN_SPEED_LEVEL);
    }

    public static synchronized void setSliderValue(double value) {
        double normalized = clamp(value, 0.0D, 1.0D);
        speedLevel = clamp((int) Math.round(MIN_SPEED_LEVEL
                + normalized * (MAX_SPEED_LEVEL - MIN_SPEED_LEVEL)), MIN_SPEED_LEVEL, MAX_SPEED_LEVEL);
    }

    public static synchronized String getSpeedLabel() {
        return "Hız: " + speedLevel + " / " + MAX_SPEED_LEVEL;
    }

    public static synchronized void save() {
        if (configFile == null) {
            configFile = FabricLoader.getInstance().getConfigDir().resolve("eyyobuild.properties");
        }
        Properties properties = new Properties();
        properties.setProperty("speedLevel", Integer.toString(speedLevel));
        try {
            Files.createDirectories(configFile.getParent());
            try (OutputStream output = Files.newOutputStream(configFile)) {
                properties.store(output, "EYYOBUILD client settings");
            }
        } catch (IOException exception) {
            System.err.println("[EYYOBUILD] Ayarlar kaydedilemedi: " + exception.getMessage());
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
