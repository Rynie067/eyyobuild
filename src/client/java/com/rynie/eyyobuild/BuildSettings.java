package com.rynie.eyyobuild;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Persistent client-side placement-rate setting (1..20 blocks per second). */
public final class BuildSettings {
    private static final int TICKS_PER_SECOND = 20;
    private static final int MIN_BLOCKS_PER_SECOND = 1;
    private static final int MAX_BLOCKS_PER_SECOND = TICKS_PER_SECOND;
    private static final int DEFAULT_BLOCKS_PER_SECOND = MAX_BLOCKS_PER_SECOND;

    private static int blocksPerSecond = DEFAULT_BLOCKS_PER_SECOND;
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

            String savedRate = properties.getProperty("blocksPerSecond");
            if (savedRate != null) {
                blocksPerSecond = clamp(Integer.parseInt(savedRate.trim()),
                        MIN_BLOCKS_PER_SECOND, MAX_BLOCKS_PER_SECOND);
                return;
            }

            // Migrate the previous 1..100 speed slider to an approximate blocks/second rate.
            String savedLevel = properties.getProperty("speedLevel");
            if (savedLevel != null) {
                int oldLevel = Integer.parseInt(savedLevel.trim());
                blocksPerSecond = clamp((int) Math.round(oldLevel
                                * (double) MAX_BLOCKS_PER_SECOND / 100.0D),
                        MIN_BLOCKS_PER_SECOND, MAX_BLOCKS_PER_SECOND);
                return;
            }

            // Older builds stored the interaction delay directly in game ticks.
            String legacyDelay = properties.getProperty("placementDelayTicks");
            if (legacyDelay != null) {
                int oldDelay = Math.max(1, Integer.parseInt(legacyDelay.trim()));
                blocksPerSecond = clamp((int) Math.round(
                                TICKS_PER_SECOND / (double) oldDelay),
                        MIN_BLOCKS_PER_SECOND, MAX_BLOCKS_PER_SECOND);
            }
        } catch (IOException | NumberFormatException exception) {
            System.err.println("[EYYOBUILD] Ayarlar okunamadı; varsayılan hız kullanılacak: "
                    + exception.getMessage());
            blocksPerSecond = DEFAULT_BLOCKS_PER_SECOND;
        }
    }

    /** Converts the selected target rate to Baritone's minimum whole-game-tick interval. */
    public static synchronized int getActionDelayTicks() {
        return Math.max(1, (TICKS_PER_SECOND + blocksPerSecond - 1) / blocksPerSecond);
    }

    public static synchronized int getBlocksPerSecond() {
        return blocksPerSecond;
    }

    public static synchronized double getSliderValue() {
        return (double) (blocksPerSecond - MIN_BLOCKS_PER_SECOND)
                / (MAX_BLOCKS_PER_SECOND - MIN_BLOCKS_PER_SECOND);
    }

    public static synchronized void setSliderValue(double value) {
        double normalized = clamp(value, 0.0D, 1.0D);
        blocksPerSecond = clamp((int) Math.round(MIN_BLOCKS_PER_SECOND
                + normalized * (MAX_BLOCKS_PER_SECOND - MIN_BLOCKS_PER_SECOND)),
                MIN_BLOCKS_PER_SECOND, MAX_BLOCKS_PER_SECOND);
    }

    public static synchronized String getSpeedLabel() {
        return "Blok hızı: " + blocksPerSecond + " blok/sn";
    }

    public static synchronized void save() {
        if (configFile == null) {
            configFile = FabricLoader.getInstance().getConfigDir().resolve("eyyobuild.properties");
        }
        Properties properties = new Properties();
        properties.setProperty("blocksPerSecond", Integer.toString(blocksPerSecond));
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
