package com.rynie.eyyobuild;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Persistent client-side requested placement rate (1..100 blocks per second). */
public final class BuildSettings {
    private static final int TICKS_PER_SECOND = 20;
    private static final int MIN_BLOCKS_PER_SECOND = 1;
    private static final int MAX_BLOCKS_PER_SECOND = 100;
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

            // Preserve a rate saved by alpha.5 (which used a 1..20 range).
            String savedRate = properties.getProperty("blocksPerSecond");
            if (savedRate != null) {
                blocksPerSecond = clamp(Integer.parseInt(savedRate.trim()),
                        MIN_BLOCKS_PER_SECOND, MAX_BLOCKS_PER_SECOND);
                return;
            }

            // Migrate the previous 1..100 delay slider to its approximate effective rate.
            String savedLevel = properties.getProperty("speedLevel");
            if (savedLevel != null) {
                int oldLevel = Integer.parseInt(savedLevel.trim());
                int oldDelay = Math.max(1, 101 - oldLevel);
                blocksPerSecond = clamp((int) Math.round(TICKS_PER_SECOND / (double) oldDelay),
                        MIN_BLOCKS_PER_SECOND, MAX_BLOCKS_PER_SECOND);
                return;
            }

            // Older builds stored the interaction delay directly in game ticks.
            String legacyDelay = properties.getProperty("placementDelayTicks");
            if (legacyDelay != null) {
                int oldDelay = Math.max(1, Integer.parseInt(legacyDelay.trim()));
                blocksPerSecond = clamp((int) Math.round(TICKS_PER_SECOND / (double) oldDelay),
                        MIN_BLOCKS_PER_SECOND, MAX_BLOCKS_PER_SECOND);
            }
        } catch (IOException | NumberFormatException exception) {
            System.err.println("[EYYOBUILD] Ayarlar okunamadı; varsayılan hız kullanılacak: "
                    + exception.getMessage());
            blocksPerSecond = DEFAULT_BLOCKS_PER_SECOND;
        }
    }

    /**
     * Converts the requested rate to Baritone's whole-game-tick interval.
     * The current builder confirms one placement at a time and runs on 20 client
     * ticks per second, so requests above 20 use the fastest one-tick interval.
     */
    public static synchronized int getActionDelayTicks() {
        int effectiveRate = Math.min(blocksPerSecond, TICKS_PER_SECOND);
        return Math.max(1, (TICKS_PER_SECOND + effectiveRate - 1) / effectiveRate);
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
