package com.rynie.eyyobuild;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IBuilderProcess;
import baritone.api.schematic.IStaticSchematic;
import com.rynie.eyyobuild.schematic.SchematicBlock;
import com.rynie.eyyobuild.schematic.SchematicSummary;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
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

    // A supply run temporarily hands pathing from the builder to a container trip.
    // The active Litematica placement is reloaded after the player returns.
    private enum SupplyPhase { IDLE, TO_CONTAINER, OPENING_CONTAINER, TRANSFERRING, RETURNING }
    private static SupplyPhase supplyPhase = SupplyPhase.IDLE;
    private static ICustomGoalProcess customGoal;
    private static BlockPos returnPosition;
    private static BlockPos activeStorage;
    private static List<BlockPos> supplyCandidates = List.of();
    private static int supplyCandidateIndex;
    private static int supplyTicks;
    private static int transferSlotIndex;
    private static int pendingTransferSlot = -1;
    private static Block pendingTransferBlock;
    private static int pendingTransferBefore;
    private static int pendingTransferTicks;
    private static int activeMenuId = -1;
    private static int supplyCooldownTicks;
    private static int remainingScanTicks;
    private static boolean remainingScanAvailable;
    private static int placementIndex = -1;
    private static java.nio.file.Path selectedSchematicPath;
    private static Map<Block, Integer> requiredBlockCounts = Map.of();

    // Runtime memory for this build session: last known contents indexed by world position.
    // Every entry is refreshed from a real, server-opened container menu before it is trusted.
    private static final Map<BlockPos, Map<String, Integer>> storageMemory = new LinkedHashMap<>();
    private static final Set<BlockPos> failedStoragePositions = new HashSet<>();
    private static final int STORAGE_RADIUS = 24;
    private static final int STORAGE_VERTICAL_RADIUS = 12;
    private static final int STORAGE_OPEN_TIMEOUT_TICKS = 40;
    private static final int REMAINING_SCAN_INTERVAL_TICKS = 100;
    private static final int LOW_MATERIAL_WATERMARK = 8;
    private static final int MATERIAL_BATCH_LIMIT = 64;
    private static final long MAX_PLACEMENT_SCAN_BLOCKS = 8_000_000L;

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

        storageMemory.clear();
        failedStoragePositions.clear();
        supplyCooldownTicks = 0;
        requiredBlockCounts = Map.of();
        remainingScanTicks = 0;
        remainingScanAvailable = false;
        selectedSchematicPath = schematic.path();
        placementIndex = placement.placementIndex();

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
            remainingScanAvailable = refreshRemainingRequirements(client);
            schematicBlockTypes = resolveSchematicBlockTypes(schematic);
            if (schematicBlockTypes.isEmpty()) {
                restoreSettings();
                clearReferences();
                status = "Şemadaki blok türleri oyun kayıt defterinden çözümlenemedi.";
                return false;
            }

            refreshUnavailableMaterials(client, true);
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
            if (remainingScanAvailable && !getNeededBlockCounts(client).isEmpty()) {
                beginSupplyRun(client);
            } else if (!remainingScanAvailable) {
                status = "İnşaat başladı; Litematica kalan blok listesi okunamadı, otomatik depo araması kapalı.";
            }
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

        applyActionSpeed();

        if (supplyCooldownTicks > 0) supplyCooldownTicks--;
        if (supplyPhase != SupplyPhase.IDLE) {
            tickSupplyRun(client);
            return;
        }

        if (!builder.isActive()) {
            finish("Baritone inşaat görevi sona erdi.");
            return;
        }

        refreshUnavailableMaterials(client, false);

        if (++remainingScanTicks >= REMAINING_SCAN_INTERVAL_TICKS) {
            remainingScanTicks = 0;
            remainingScanAvailable = refreshRemainingRequirements(client);
        }

        // Refill only when a still-needed material reaches its low-water mark.
        // This avoids filling the inventory with the entire schematic's total count.
        if (remainingScanAvailable && supplyCooldownTicks == 0 && !getNeededBlockCounts(client).isEmpty()) {
            if (beginSupplyRun(client)) return;
        }

        String materialStatus = unavailableBlockTypes.isEmpty()
                ? ""
                : " | " + unavailableBlockTypes.size() + " blok türü envanterde yok";
        if (builder.isPaused()) {
            status = "Baritone beklemede: rota/yerleştirme yeniden deneniyor" + materialStatus;
        } else if (pathing != null && pathing.isPathing()) {
            status = "İnşaat alanına rota üzerinde ilerliyor" + materialStatus;
        } else if (pathing != null && pathing.hasPath()) {
            status = "İnşaat rotası hazır" + materialStatus;
        } else {
            status = "Bloklar yerleştiriliyor" + materialStatus;
        }
    }

    static synchronized boolean isActive() {
        return sessionOwned && (supplyPhase != SupplyPhase.IDLE || (builder != null && builder.isActive()));
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

    /**
     * Ask Baritone's own Litematica bridge for the transformed, enabled-subregion
     * schematic. Its origin already accounts for rotation, mirror and enabled
     * subregion offsets, so the remaining-material scan matches the builder.
     */
    private static boolean refreshRemainingRequirements(Minecraft client) {
        if (client == null || client.level == null || placementIndex < 0) return false;
        try {
            Class<?> helperClass = Class.forName("baritone.utils.schematic.litematica.LitematicaHelper");
            Object pair = helperClass.getMethod("getSchematic", int.class).invoke(null, placementIndex);
            if (pair == null) throw new IllegalStateException("Litematica şematik dönüşümü boş döndü.");

            Object schematicValue = pair.getClass().getMethod("first").invoke(pair);
            Object originValue = pair.getClass().getMethod("second").invoke(pair);
            if (!(schematicValue instanceof IStaticSchematic transformed)
                    || !(originValue instanceof Vec3i origin)) {
                throw new IllegalStateException("Baritone dönüştürülmüş şematik verisi okunamadı.");
            }

            int width = transformed.widthX();
            int height = transformed.heightY();
            int length = transformed.lengthZ();
            long volume = (long) width * height * length;
            if (width <= 0 || height <= 0 || length <= 0 || volume > MAX_PLACEMENT_SCAN_BLOCKS) {
                throw new IllegalStateException("Etkin Litematica alanı güvenli tarama sınırını aşıyor.");
            }

            Map<Block, Integer> remaining = new LinkedHashMap<>();
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    for (int x = 0; x < width; x++) {
                        BlockState desired = transformed.getDirect(x, y, z);
                        if (desired == null || desired.isAir()
                                || !(desired.getBlock().asItem() instanceof BlockItem)) {
                            continue;
                        }

                        BlockPos worldPos = new BlockPos(
                                origin.getX() + x,
                                origin.getY() + y,
                                origin.getZ() + z
                        );
                        BlockState current = client.level.getBlockState(worldPos);
                        if (!current.equals(desired)) {
                            remaining.merge(desired.getBlock(), 1, Integer::sum);
                        }
                    }
                }
            }

            requiredBlockCounts = Map.copyOf(remaining);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            status = "Kalan Litematica blokları taranamadı: " + safeMessage(exception);
            return false;
        }
    }

    private static int countBlockInInventory(Minecraft client, Block block) {
        if (client == null || client.player == null) return 0;
        int count = 0;
        var items = client.player.getInventory().getNonEquipmentItems();
        for (int i = 0; i < Math.min(36, items.size()); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() == block) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int targetStockFor(Block block, int remaining) {
        int maxStack = Math.max(1, block.asItem().getDefaultMaxStackSize());
        return Math.min(remaining, Math.min(maxStack, MATERIAL_BATCH_LIMIT));
    }

    private static Map<Block, Integer> getNeededBlockCounts(Minecraft client) {
        Map<Block, Integer> needed = new LinkedHashMap<>();
        for (Map.Entry<Block, Integer> entry : requiredBlockCounts.entrySet()) {
            int current = countBlockInInventory(client, entry.getKey());
            int target = targetStockFor(entry.getKey(), entry.getValue());
            int lowWatermark = Math.min(LOW_MATERIAL_WATERMARK, target);
            if (current <= lowWatermark && current < target) {
                needed.put(entry.getKey(), target - current);
            }
        }
        return needed;
    }

    private static boolean beginSupplyRun(Minecraft client) {
        Map<Block, Integer> needed = getNeededBlockCounts(client);
        if (needed.isEmpty()) return false;

        List<BlockPos> candidates = findStorageCandidates(client, needed);
        if (candidates.isEmpty()) {
            supplyCooldownTicks = 160;
            status = "Yakında eksik şematik malzemesi içeren erişilebilir sandık/varil/shulker bulunamadı.";
            return false;
        }

        returnPosition = client.player.blockPosition();
        supplyCandidates = candidates;
        supplyCandidateIndex = 0;
        supplyTicks = 0;
        activeStorage = null;
        pendingTransferSlot = -1;
        pendingTransferBlock = null;
        customGoal = baritone.getCustomGoalProcess();

        // Releasing the builder process preserves no stale pathing command. We reload
        // the same active Litematica placement after the material run returns.
        try {
            if (builder.isActive()) builder.onLostControl();
            if (pathing != null) pathing.cancelEverything();
            supplyPhase = SupplyPhase.TO_CONTAINER;
            travelToNextStorage();
            status = "Yakındaki depoya gidiliyor; " + supplyCandidates.size() + " konteyner bulundu.";
            return true;
        } catch (RuntimeException exception) {
            supplyPhase = SupplyPhase.IDLE;
            supplyCooldownTicks = 160;
            status = "Depoya giderken hata: " + safeMessage(exception);
            return false;
        }
    }

    private static List<BlockPos> findStorageCandidates(Minecraft client, Map<Block, Integer> needed) {
        BlockPos origin = client.player.blockPosition();
        Set<BlockPos> unique = new HashSet<>();
        List<BlockPos> result = new ArrayList<>();

        for (int x = origin.getX() - STORAGE_RADIUS; x <= origin.getX() + STORAGE_RADIUS; x++) {
            for (int z = origin.getZ() - STORAGE_RADIUS; z <= origin.getZ() + STORAGE_RADIUS; z++) {
                for (int y = origin.getY() - STORAGE_VERTICAL_RADIUS;
                     y <= origin.getY() + STORAGE_VERTICAL_RADIUS; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!unique.add(pos)) continue;
                    Block block = client.level.getBlockState(pos).getBlock();
                    if (!(block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock)) {
                        continue;
                    }
                    if (failedStoragePositions.contains(pos)) continue;

                    Map<String, Integer> remembered = storageMemory.get(pos);
                    if (remembered != null && !rememberedContainsNeeded(remembered, needed)) {
                        continue;
                    }
                    result.add(pos);
                }
            }
        }

        result.sort(Comparator
                .comparingInt((BlockPos pos) -> {
                    Map<String, Integer> remembered = storageMemory.get(pos);
                    return remembered != null && rememberedContainsNeeded(remembered, needed) ? 0 : 1;
                })
                .thenComparingDouble(pos -> pos.distToCenterSqr(client.player.position())));
        return result;
    }

    private static boolean rememberedContainsNeeded(Map<String, Integer> remembered, Map<Block, Integer> needed) {
        for (Block block : needed.keySet()) {
            var itemId = BuiltInRegistries.ITEM.getKey(block.asItem());
            if (itemId != null && remembered.getOrDefault(itemId.toString(), 0) > 0) return true;
        }
        return false;
    }

    private static void travelToNextStorage() {
        while (supplyCandidateIndex < supplyCandidates.size()) {
            BlockPos candidate = supplyCandidates.get(supplyCandidateIndex++);
            if (failedStoragePositions.contains(candidate)) continue;
            activeStorage = candidate;
            supplyTicks = 0;
            customGoal.setGoalAndPath(new GoalGetToBlock(candidate));
            supplyPhase = SupplyPhase.TO_CONTAINER;
            status = "Depoya gidiliyor: " + candidate.toShortString();
            return;
        }
        beginReturn("Yakındaki depolar tarandı.");
    }

    private static void tickSupplyRun(Minecraft client) {
        if (customGoal == null || builder == null) {
            finish("Depo görevi kayboldu; inşaat durduruldu.");
            return;
        }

        switch (supplyPhase) {
            case TO_CONTAINER -> {
                if (activeStorage == null) {
                    travelToNextStorage();
                    return;
                }
                if (client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(activeStorage)) <= 20.25D) {
                    if (lookAtAndOpenStorage(client, activeStorage)) {
                        supplyPhase = SupplyPhase.OPENING_CONTAINER;
                        supplyTicks = 0;
                        status = "Konteyner açılıyor: " + activeStorage.toShortString();
                    }
                    return;
                }

                supplyTicks++;
                if (supplyTicks > 600
                        || (supplyTicks > 80 && pathing != null && !pathing.isPathing() && !pathing.hasPath())) {
                    failedStoragePositions.add(activeStorage);
                    status = "Depoya güvenli rota bulunamadı: " + activeStorage.toShortString();
                    travelToNextStorage();
                }
            }
            case OPENING_CONTAINER -> {
                if (client.player.containerMenu != client.player.inventoryMenu) {
                    activeMenuId = client.player.containerMenu.containerId;
                    transferSlotIndex = 0;
                    pendingTransferSlot = -1;
                    pendingTransferTicks = 0;
                    rememberOpenStorageContents(client.player.containerMenu, client.player);
                    supplyPhase = SupplyPhase.TRANSFERRING;
                    status = "Depo içeriği kaydedildi; gerekli bloklar alınıyor.";
                    return;
                }
                if (++supplyTicks >= STORAGE_OPEN_TIMEOUT_TICKS) {
                    failedStoragePositions.add(activeStorage);
                    status = "Konteyner açılamadı veya sunucu erişimi reddetti: " + activeStorage.toShortString();
                    travelToNextStorage();
                }
            }
            case TRANSFERRING -> tickStorageTransfer(client);
            case RETURNING -> {
                if (client.player.blockPosition().equals(returnPosition)) {
                    resumeBuilderAfterSupply(client, false);
                    return;
                }
                if (++supplyTicks > 600
                        || (supplyTicks > 100 && pathing != null && !pathing.isPathing() && !pathing.hasPath())) {
                    resumeBuilderAfterSupply(client, true);
                }
            }
            case IDLE -> {
            }
        }
    }

    private static boolean lookAtAndOpenStorage(Minecraft client, BlockPos pos) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 target = Vec3.atCenterOf(pos);
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));

        float yawDelta = wrapDegrees(targetYaw - client.player.getYRot());
        float pitchDelta = targetPitch - client.player.getXRot();
        if (Math.abs(yawDelta) > 1.0F || Math.abs(pitchDelta) > 1.0F) {
            client.player.setYRot(stepAngle(client.player.getYRot(), targetYaw, 35.0F));
            client.player.setXRot(stepLinear(client.player.getXRot(), targetPitch, 25.0F));
            return false;
        }

        HitResult result = client.player.pick(4.5D, 1.0F, false);
        if (!(result instanceof BlockHitResult blockHit)
                || result.getType() != HitResult.Type.BLOCK
                || !blockHit.getBlockPos().equals(pos)) {
            status = "Konteynere bakılıyor: " + pos.toShortString();
            return false;
        }

        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, blockHit);
        return true;
    }

    private static void tickStorageTransfer(Minecraft client) {
        AbstractContainerMenu menu = client.player.containerMenu;
        if (menu == client.player.inventoryMenu || menu.containerId != activeMenuId) {
            failedStoragePositions.add(activeStorage);
            travelToNextStorage();
            return;
        }

        if (pendingTransferSlot >= 0) {
            int after = countBlockInInventory(client, pendingTransferBlock);
            if (after > pendingTransferBefore) {
                rememberOpenStorageContents(menu, client.player);
                Slot source = pendingTransferSlot < menu.slots.size() ? menu.slots.get(pendingTransferSlot) : null;
                Block transferredBlock = pendingTransferBlock;
                pendingTransferSlot = -1;
                pendingTransferBlock = null;
                pendingTransferTicks = 0;
                if (source != null && !source.getItem().isEmpty()
                        && transferredBlock != null
                        && countBlockInInventory(client, transferredBlock) < targetStockFor(
                                transferredBlock, requiredBlockCounts.getOrDefault(transferredBlock, 0))) {
                    // The destination had room for only part of this stack; retry the remaining source.
                    transferSlotIndex = Math.max(0, source.index);
                } else {
                    transferSlotIndex++;
                }
                return;
            }

            if (++pendingTransferTicks >= 10) {
                pendingTransferSlot = -1;
                pendingTransferBlock = null;
                pendingTransferTicks = 0;
                transferSlotIndex++;
                status = "Envanter dolu veya bu yığın taşınamadı; diğer malzemeler kontrol ediliyor.";
            }
            return;
        }

        if (transferSlotIndex >= menu.slots.size()) {
            rememberOpenStorageContents(menu, client.player);
            client.player.closeContainer();
            refreshUnavailableMaterials(client, true);
            if (getNeededBlockCounts(client).isEmpty()) {
                beginReturn("Gerekli malzemeler toplandı.");
            } else {
                travelToNextStorage();
            }
            return;
        }

        Slot slot = menu.slots.get(transferSlotIndex);
        if (slot.container == client.player.getInventory()) {
            transferSlotIndex++;
            return;
        }

        ItemStack stack = slot.getItem();
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            transferSlotIndex++;
            return;
        }

        Block block = blockItem.getBlock();
        int remaining = requiredBlockCounts.getOrDefault(block, 0);
        int target = targetStockFor(block, remaining);
        int current = countBlockInInventory(client, block);
        if (target <= current) {
            transferSlotIndex++;
            return;
        }

        pendingTransferSlot = transferSlotIndex;
        pendingTransferBlock = block;
        pendingTransferBefore = current;
        pendingTransferTicks = 0;
        client.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, client.player);
        status = "Malzeme alınıyor: " + BuiltInRegistries.BLOCK.getKey(block);
    }

    private static void rememberOpenStorageContents(AbstractContainerMenu menu, net.minecraft.world.entity.player.Player player) {
        if (activeStorage == null || menu == null) return;
        Map<String, Integer> contents = new LinkedHashMap<>();
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            var itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemId != null) contents.merge(itemId.toString(), stack.getCount(), Integer::sum);
        }
        storageMemory.put(activeStorage, Map.copyOf(contents));
    }

    private static void beginReturn(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.player.containerMenu != client.player.inventoryMenu) {
            rememberOpenStorageContents(client.player.containerMenu, client.player);
            client.player.closeContainer();
        }
        activeStorage = null;
        if (returnPosition == null || client.player == null) {
            resumeBuilderAfterSupply(client, true);
            return;
        }
        supplyPhase = SupplyPhase.RETURNING;
        supplyTicks = 0;
        customGoal.setGoalAndPath(new GoalBlock(returnPosition));
        status = message + " Geri dönülüyor: " + returnPosition.toShortString();
    }

    private static void resumeBuilderAfterSupply(Minecraft client, boolean returnRouteFailed) {
        try {
            if (client.player != null && client.player.containerMenu != client.player.inventoryMenu) {
                client.player.closeContainer();
            }
            if (customGoal != null) customGoal.onLostControl();
            if (pathing != null) pathing.cancelEverything();

            refreshUnavailableMaterials(client, true);
            LitematicaPlacementBridge.Result placement =
                    LitematicaPlacementBridge.findSelectedPlacement(selectedSchematicPath);
            if (!placement.success()) {
                stop("Malzeme toplandı ancak Litematica yerleşimi artık seçili değil: " + placement.message());
                return;
            }
            placementIndex = placement.placementIndex();
            remainingScanAvailable = refreshRemainingRequirements(client);
            remainingScanTicks = 0;
            builder.buildOpenLitematic(placementIndex);
            if (!builder.isActive()) {
                stop("Malzemeden sonra Baritone inşaatı yeniden başlatamadı.");
                return;
            }

            supplyPhase = SupplyPhase.IDLE;
            supplyCooldownTicks = 80;
            String returnMessage = returnRouteFailed
                    ? "Geri dönüş rotası tamamlanamadı; Baritone mevcut konumdan inşaata devam ediyor."
                    : "Depodan geri dönüldü; inşaat kaldığı yerden devam ediyor.";
            status = returnMessage + " Kayıtlı depo: " + storageMemory.size();
        } catch (RuntimeException | LinkageError exception) {
            stop("Malzeme toplandı ancak inşaat devam ettirilemedi: " + safeMessage(exception));
        }
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    private static float stepAngle(float current, float target, float maxStep) {
        float delta = wrapDegrees(target - current);
        return current + Math.max(-maxStep, Math.min(maxStep, delta));
    }

    private static float stepLinear(float current, float target, float maxStep) {
        float delta = target - current;
        return current + Math.max(-maxStep, Math.min(maxStep, delta));
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
            var inventoryItems = client.player.getInventory().getNonEquipmentItems();
            int slots = Math.min(36, inventoryItems.size());
            for (int slot = 0; slot < slots; slot++) {
                ItemStack stack = inventoryItems.get(slot);
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
        int delay = Math.max(1, BuildSettings.getActionDelayTicks());
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
            Minecraft client = Minecraft.getInstance();
            if (client.player != null && client.player.containerMenu != client.player.inventoryMenu
                    && supplyPhase != SupplyPhase.IDLE) {
                client.player.closeContainer();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (customGoal != null) customGoal.onLostControl();
        } catch (RuntimeException ignored) {
        }
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
        remainingScanTicks = 0;
        remainingScanAvailable = false;
        supplyPhase = SupplyPhase.IDLE;
        customGoal = null;
        returnPosition = null;
        activeStorage = null;
        supplyCandidates = List.of();
        supplyCandidateIndex = 0;
        supplyTicks = 0;
        transferSlotIndex = 0;
        pendingTransferSlot = -1;
        pendingTransferBlock = null;
        pendingTransferBefore = 0;
        pendingTransferTicks = 0;
        activeMenuId = -1;
        placementIndex = -1;
        selectedSchematicPath = null;
        requiredBlockCounts = Map.of();
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
