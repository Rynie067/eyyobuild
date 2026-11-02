package com.rynie.eyyobuild;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.process.IBuilderProcess;
import com.rynie.eyyobuild.schematic.SchematicBlock;
import com.rynie.eyyobuild.schematic.SchematicSummary;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * EYYOBUILD's adapter to the official Baritone 26.2 builder/pathing engine.
 * Baritone is shipped as an intact nested Fabric mod, not copied into EYYOBUILD.
 */
final class BaritoneBuildBridge {
    private static IBaritone baritone;
    private static IBuilderProcess builder;
    private static IPathingBehavior pathing;
    private static Settings settings;
    private static SettingsSnapshot previousSettings;
    private static boolean sessionOwned;
    private static Set<Block> schematicBlockTypes = Set.of();
    private static Set<Block> unavailableBlockTypes = Set.of();
    private static int inventoryRefreshTicks;
    private static String status = "Hazır";

    private BaritoneBuildBridge() {}

    static synchronized boolean start(Minecraft client, SchematicSummary schematic) {
        if (sessionOwned) {
            if (isActive()) {
                status = "Baritone zaten inşaat yapıyor.";
                return false;
            }
            finish("Önceki Baritone görevi sona erdi.");
        }
        if (client == null || client.player == null || client.level == null) {
            status = "Önce bir dünyaya gir.";
            return false;
        }
        if (schematic == null) {
            status = "Önce bir .litematic dosyası seç.";
            return false;
        }
        if (!schematic.readable() || schematic.blocks().isEmpty()) {
            status = schematic.error() == null || schematic.error().isBlank()
                    ? "Şemada yerleştirilecek blok bulunamadı."
                    : "Şema okunamadı: " + schematic.error();
            return false;
        }

        LitematicaPlacementBridge.Result placement =
                LitematicaPlacementBridge.findSelectedPlacement(schematic.path());
        if (!placement.success()) {
            status = placement.message();
            return false;
        }

        try {
            baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            builder = baritone.getBuilderProcess();
            pathing = baritone.getPathingBehavior();
            settings = BaritoneAPI.getSettings();

            if (builder.isActive() || pathing.isPathing() || pathing.hasPath()) {
                status = "Baritone başka bir görev yürütüyor. Önce o görevi durdur.";
                clearReferences();
                return false;
            }

            previousSettings = SettingsSnapshot.capture(settings);
            schematicBlockTypes = resolveSchematicBlockTypes(schematic);
            if (schematicBlockTypes.isEmpty()) {
                restoreSettings();
                clearReferences();
                status = "Şemadaki blok türleri oyun kayıt defterinden çözümlenemedi.";
                return false;
            }

            refreshUnavailableMaterials(client, true);
            if (unavailableBlockTypes.size() >= schematicBlockTypes.size()) {
                restoreSettings();
                clearReferences();
                status = "Envanterinde şemaya ait yerleştirilebilir blok yok. Birkaç gerekli blok alıp yeniden başlat.";
                return false;
            }
            applyAutomationSettings();

            // Baritone reads the selected, already-transformed Litematica placement.
            // Do not apply a second rotation/mirror in its builder settings.
            builder.buildOpenLitematic(placement.placementIndex());
            if (!builder.isActive()) {
                restoreSettings();
                status = "Baritone inşaatı başlatamadı. Litematica yerleşimini tekrar seçip dene.";
                clearReferences();
                return false;
            }

            sessionOwned = true;
            status = "Baritone otomatik inşaatı başlattı.";
            return true;
        } catch (LinkageError | RuntimeException exception) {
            stopBaritoneSafely();
            restoreSettings();
            clearReferences();
            status = "Baritone başlatılamadı: " + safeMessage(exception);
            return false;
        }
    }

    static synchronized void tick(Minecraft client) {
        if (!sessionOwned || builder == null) return;

        if (client == null || client.player == null || client.level == null) {
            stop("Dünya kapandığı için Baritone görevi durduruldu.");
            return;
        }

        if (!builder.isActive()) {
            finish("Baritone inşaat görevi sona erdi.");
            return;
        }

        refreshUnavailableMaterials(client, false);
        applyActionSpeed();

        String materialStatus = unavailableBlockTypes.isEmpty()
                ? ""
                : " | " + unavailableBlockTypes.size() + " blok türü envanterde yok, atlandı";

        if (builder.isPaused()) {
            status = "Baritone beklemede: rota/yerleştirme yeniden deneniyor" + materialStatus;
        } else if (pathing != null && pathing.isPathing()) {
            status = "Envanterdeki uygun bloklara rota üzerinde ilerliyor" + materialStatus;
        } else if (pathing != null && pathing.hasPath()) {
            status = "Uygun blok hedefi için rota hazır" + materialStatus;
        } else {
            status = "Eksik blok türleri atlandı; envanterdeki diğer bloklar aranıyor" + materialStatus;
        }
    }

    static synchronized boolean isActive() {
        return sessionOwned && builder != null && builder.isActive();
    }

    static synchronized String status() {
        return status;
    }

    static synchronized void stop(String message) {
        if (sessionOwned || baritone != null || builder != null) {
            stopBaritoneSafely();
            restoreSettings();
            clearReferences();
        }
        status = message;
    }

    private static void applyAutomationSettings() {
        settings.allowBreak.value = true;
        settings.allowPlace.value = true;
        settings.allowInventory.value = true;
        settings.autoTool.value = true;
        settings.allowSprint.value = true;

        settings.allowParkour.value = true;
        settings.allowParkourAscend.value = true;
        settings.allowParkourPlace.value = true;
        settings.allowDiagonalAscend.value = true;
        settings.allowDownward.value = true;
        settings.allowWaterBucketFall.value = true;

        settings.buildSchematicRotation.value = Rotation.NONE;
        settings.buildSchematicMirror.value = Mirror.NONE;
        settings.buildOnlySelection.value = false;

        // Missing block types are filtered from the complete schematic instead
        // of forcing the builder to finish one Y layer before targeting another.
        // This lets Baritone path to any remaining placeable block in the whole
        // selected placement, even when the nearest position needs an unavailable
        // material. The filter is refreshed from the live inventory during build.
        settings.buildInLayers.value = false;
        settings.skipFailedLayers.value = false;
        settings.buildSkipBlocks.value = new ArrayList<>(unavailableBlockTypes);

        settings.mapArtMode.value = false;
        settings.buildRepeat.value = new Vec3i(0, 0, 0);
        settings.buildRepeatCount.value = 1;

        applyActionSpeed();
    }

    private static Set<Block> resolveSchematicBlockTypes(SchematicSummary schematic) {
        Set<Block> result = new LinkedHashSet<>();
        for (SchematicBlock block : schematic.blocks()) {
            String rawId = block.blockId();
            if (rawId == null || rawId.isBlank()) continue;

            int separator = rawId.indexOf(':');
            String namespace = separator >= 0 ? rawId.substring(0, separator) : "minecraft";
            String path = separator >= 0 ? rawId.substring(separator + 1) : rawId;
            try {
                Identifier id = Identifier.fromNamespaceAndPath(namespace, path);
                Block resolved = BuiltInRegistries.BLOCK.getValue(id);
                if (resolved != null && resolved != Blocks.AIR) {
                    result.add(resolved);
                }
            } catch (RuntimeException ignored) {
                // Invalid/unknown palette identifiers cannot be placed by this client.
            }
        }
        return Set.copyOf(result);
    }

    private static void refreshUnavailableMaterials(Minecraft client, boolean force) {
        if (settings == null || schematicBlockTypes.isEmpty()) return;
        if (!force && ++inventoryRefreshTicks < 10) return;
        inventoryRefreshTicks = 0;

        Set<Block> available = new HashSet<>();
        if (client != null && client.player != null) {
            var inventory = client.player.getInventory();
            int slots = Math.min(36, inventory.items.size());
            for (int slot = 0; slot < slots; slot++) {
                ItemStack stack = inventory.items.get(slot);
                if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem) {
                    available.add(blockItem.getBlock());
                }
            }
        }

        Set<Block> unavailable = new LinkedHashSet<>(schematicBlockTypes);
        unavailable.removeAll(available);
        if (!unavailable.equals(unavailableBlockTypes)) {
            unavailableBlockTypes = Set.copyOf(unavailable);
            settings.buildSkipBlocks.value = new ArrayList<>(unavailableBlockTypes);
        }
    }

    private static void applyActionSpeed() {
        if (settings == null) return;
        int delay = Math.max(4, BuildSettings.getActionDelayTicks());
        settings.rightClickSpeed.value = delay;
        settings.blockBreakSpeed.value = delay;
    }

    private static void finish(String message) {
        restoreSettings();
        clearReferences();
        status = message;
    }

    private static void stopBaritoneSafely() {
        try {
            if (pathing != null) pathing.cancelEverything();
        } catch (RuntimeException ignored) {
        }
        try {
            if (builder != null && builder.isActive()) builder.onLostControl();
        } catch (RuntimeException ignored) {
        }
    }

    private static void restoreSettings() {
        if (settings != null && previousSettings != null) {
            previousSettings.restore(settings);
        }
        previousSettings = null;
    }

    private static void clearReferences() {
        sessionOwned = false;
        builder = null;
        pathing = null;
        baritone = null;
        settings = null;
        schematicBlockTypes = Set.of();
        unavailableBlockTypes = Set.of();
        inventoryRefreshTicks = 0;
    }

    private static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private record SettingsSnapshot(
            boolean allowBreak,
            boolean allowPlace,
            boolean allowInventory,
            boolean autoTool,
            boolean allowSprint,
            boolean allowParkour,
            boolean allowParkourAscend,
            boolean allowParkourPlace,
            boolean allowDiagonalAscend,
            boolean allowDownward,
            boolean allowWaterBucketFall,
            int rightClickSpeed,
            int blockBreakSpeed,
            Rotation rotation,
            Mirror mirror,
            boolean buildOnlySelection,
            boolean buildInLayers,
            boolean skipFailedLayers,
            int layerHeight,
            boolean layerOrder,
            int startAtLayer,
            List<Block> buildSkipBlocks,
            boolean mapArtMode,
            Vec3i buildRepeat,
            int buildRepeatCount
    ) {
        static SettingsSnapshot capture(Settings s) {
            return new SettingsSnapshot(
                    s.allowBreak.value,
                    s.allowPlace.value,
                    s.allowInventory.value,
                    s.autoTool.value,
                    s.allowSprint.value,
                    s.allowParkour.value,
                    s.allowParkourAscend.value,
                    s.allowParkourPlace.value,
                    s.allowDiagonalAscend.value,
                    s.allowDownward.value,
                    s.allowWaterBucketFall.value,
                    s.rightClickSpeed.value,
                    s.blockBreakSpeed.value,
                    s.buildSchematicRotation.value,
                    s.buildSchematicMirror.value,
                    s.buildOnlySelection.value,
                    s.buildInLayers.value,
                    s.skipFailedLayers.value,
                    s.layerHeight.value,
                    s.layerOrder.value,
                    s.startAtLayer.value,
                    List.copyOf(s.buildSkipBlocks.value),
                    s.mapArtMode.value,
                    s.buildRepeat.value,
                    s.buildRepeatCount.value
            );
        }

        void restore(Settings s) {
            s.allowBreak.value = allowBreak;
            s.allowPlace.value = allowPlace;
            s.allowInventory.value = allowInventory;
            s.autoTool.value = autoTool;
            s.allowSprint.value = allowSprint;
            s.allowParkour.value = allowParkour;
            s.allowParkourAscend.value = allowParkourAscend;
            s.allowParkourPlace.value = allowParkourPlace;
            s.allowDiagonalAscend.value = allowDiagonalAscend;
            s.allowDownward.value = allowDownward;
            s.allowWaterBucketFall.value = allowWaterBucketFall;
            s.rightClickSpeed.value = rightClickSpeed;
            s.blockBreakSpeed.value = blockBreakSpeed;
            s.buildSchematicRotation.value = rotation;
            s.buildSchematicMirror.value = mirror;
            s.buildOnlySelection.value = buildOnlySelection;
            s.buildInLayers.value = buildInLayers;
            s.skipFailedLayers.value = skipFailedLayers;
            s.layerHeight.value = layerHeight;
            s.layerOrder.value = layerOrder;
            s.startAtLayer.value = startAtLayer;
            s.buildSkipBlocks.value = new ArrayList<>(buildSkipBlocks);
            s.mapArtMode.value = mapArtMode;
            s.buildRepeat.value = buildRepeat;
            s.buildRepeatCount.value = buildRepeatCount;
        }
    }
}
