package com.rynie.eyyobuild;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** Persistent client-side construction settings. */
public final class BuildSettings {
    private static final double MIN_BLOCKS_PER_SECOND = 0.1D;
    private static final double MAX_BLOCKS_PER_SECOND = 5.0D;
    private static final int MIN_ACTION_DELAY_TICKS = 3;
    private static final int MAX_ACTION_DELAY_TICKS = 199;
    private static final int DEFAULT_ACTION_DELAY_TICKS = 19;

    private static int actionDelayTicks = DEFAULT_ACTION_DELAY_TICKS;
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
            String savedDelay = properties.getProperty("placementDelayTicks");
            if (savedDelay != null) {
                actionDelayTicks = clamp(Integer.parseInt(savedDelay.trim()),
                        MIN_ACTION_DELAY_TICKS, MAX_ACTION_DELAY_TICKS);
            }
        } catch (IOException | NumberFormatException exception) {
            System.err.println("[EYYOBUILD] Ayarlar okunamadı; varsayılan değerler kullanılacak: "
                    + exception.getMessage());
            actionDelayTicks = DEFAULT_ACTION_DELAY_TICKS;
        }
    }

    public static synchronized int getActionDelayTicks() {
        return actionDelayTicks;
    }

    public static synchronized double getSliderValue() {
        double blocksPerSecond = 20.0D / (actionDelayTicks + 1.0D);
        return clamp((blocksPerSecond - MIN_BLOCKS_PER_SECOND)
                / (MAX_BLOCKS_PER_SECOND - MIN_BLOCKS_PER_SECOND), 0.0D, 1.0D);
    }

    public static synchronized void setSliderValue(double value) {
        double normalized = clamp(value, 0.0D, 1.0D);
        double blocksPerSecond = MIN_BLOCKS_PER_SECOND
                + normalized * (MAX_BLOCKS_PER_SECOND - MIN_BLOCKS_PER_SECOND);
        int delay = (int) Math.round(20.0D / blocksPerSecond) - 1;
        actionDelayTicks = clamp(delay, MIN_ACTION_DELAY_TICKS, MAX_ACTION_DELAY_TICKS);
    }

    public static synchronized String getSpeedLabel() {
        double blocksPerSecond = 20.0D / (actionDelayTicks + 1.0D);
        String formatted = String.format(Locale.ROOT, "%.1f", blocksPerSecond).replace('.', ',');
        return "Hız: " + formatted + " blok/sn";
    }

    public static synchronized void save() {
        if (configFile == null) {
            configFile = FabricLoader.getInstance().getConfigDir().resolve("eyyobuild.properties");
        }

        Properties properties = new Properties();
        properties.setProperty("placementDelayTicks", Integer.toString(actionDelayTicks));
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
