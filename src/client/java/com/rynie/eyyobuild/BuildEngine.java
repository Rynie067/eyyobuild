package com.rynie.eyyobuild;

import com.rynie.eyyobuild.schematic.SchematicBlock;
import com.rynie.eyyobuild.schematic.SchematicSummary;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Places decoded blocks at the active Litematica placement origin.
 * Does not write to movement keys, sneak, or move the player.
 */
public final class BuildEngine {
    private static final double REACH_SQUARED = 20.25;

    private static boolean active;
    private static String status = "Hazır.";
    private static List<SchematicBlock> blocks = List.of();
    private static BlockPos anchor;
    private static int cursor;
    private static String rotation = "NONE";
    private static String mirror = "NONE";
    private static String placementName = "";

    private BuildEngine() {}

    public static synchronized boolean isActive() {
        return active;
    }

    public static synchronized String status() {
        return status + (blocks.isEmpty() ? "" : "  " + Math.min(cursor, blocks.size()) + "/" + blocks.size());
    }

    public static synchronized void start(Minecraft client, SchematicSummary schematic) {
        stop("Hazır.");
        if (client.player == null || client.level == null) {
            status = "Önce bir dünyaya gir.";
            return;
        }
        if (schematic == null || !schematic.readable() || schematic.blocks().isEmpty()) {
            status = "Önce blok verisi olan bir .litematic seç.";
            return;
        }

        LitematicaPlacementBridge.Result placement =
                LitematicaPlacementBridge.findSelectedPlacement(schematic.path());
        if (!placement.success()) {
            status = placement.message();
            return;
        }

        blocks = new ArrayList<>(schematic.blocks());
        blocks.sort(Comparator.comparingInt(SchematicBlock::y)
                .thenComparingInt(SchematicBlock::z)
                .thenComparingInt(SchematicBlock::x));
        anchor = placement.origin();
        rotation = placement.rotation();
        mirror = placement.mirror();
        placementName = placement.placementName();
        cursor = 0;
        active = true;
        status = "Şema konumu alındı: " + anchor.toShortString() + " (" + placementName + ").";
    }

    public static synchronized void stop(String message) {
        active = false;
        status = message;
        blocks = List.of();
        anchor = null;
        cursor = 0;
        rotation = "NONE";
        mirror = "NONE";
        placementName = "";
    }

    public static synchronized void tick(Minecraft client) {
        if (!active) return;
        if (client.player == null || client.level == null) {
            stop("Dünya kapandı; inşaat durduruldu.");
            return;
        }
        if (cursor >= blocks.size()) {
            stop("Şema tamamlandı!");
            return;
        }

        SchematicBlock schematicBlock = blocks.get(cursor);
        BlockPos offset = transform(schematicBlock.x(), schematicBlock.y(), schematicBlock.z());
        BlockPos target = anchor.offset(offset.getX(), offset.getY(), offset.getZ());

        var blockReference = BuiltInRegistries.BLOCK.get(Identifier.parse(schematicBlock.blockId()));
        if (blockReference.isEmpty()) {
            status = "Bilinmeyen blok: " + schematicBlock.blockId();
            active = false;
            return;
        }
        Block expectedBlock = blockReference.get().value();

        BlockState existing = client.level.getBlockState(target);
        if (existing.getBlock() == expectedBlock) {
            cursor++;
            status = "Bloklar yerleştiriliyor...";
            return;
        }

        if (client.player.distanceToSqr(Vec3.atCenterOf(target)) > REACH_SQUARED) {
            status = "Hedef menzil dışında (" + target.toShortString()
                    + "). Uçarak/yürüyerek yaklaş; oyuncu hareketine dokunulmuyor.";
            return;
        }

        if (!existing.isAir()) {
            if (client.level.getBlockEntity(target) != null) {
                status = "Blok varlığı korunuyor: " + target.toShortString();
                active = false;
                return;
            }
            boolean destroyed = client.gameMode.destroyBlock(target);
            status = destroyed
                    ? "Yanlış blok kaldırılıyor: " + target.toShortString()
                    : "Blok kırılamadı; sunucu işlemi reddetmiş olabilir.";
            if (!destroyed) active = false;
            return;
        }

        int hotbarSlot = findBlockInHotbar(client, expectedBlock);
        if (hotbarSlot < 0) {
            status = "Eksik eşya: " + schematicBlock.blockId() + " (sıcak çubuk)";
            return;
        }

        Support support = findSupport(client, target);
        if (support == null) {
            status = "Havada desteksiz blok: " + target.toShortString()
                    + ". İlk blok için komşu sağlam bir destek bloğu gerekir.";
            return;
        }

        client.player.getInventory().setSelectedSlot(hotbarSlot);
        float originalYaw = client.player.getYRot();
        float placementYaw = yawForFacing(schematicBlock.properties().get("facing"));
        try {
            if (!Float.isNaN(placementYaw)) client.player.setYRot(placementYaw);
            BlockHitResult hit = new BlockHitResult(
                    support.hitLocation(), support.clickedFace(), support.blockPos(), false
            );
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
            status = "Blok yerleştiriliyor: " + target.toShortString();
        } finally {
            client.player.setYRot(originalYaw);
        }
    }

    private static BlockPos transform(int sourceX, int sourceY, int sourceZ) {
        int x = sourceX;
        int y = sourceY;
        int z = sourceZ;

        // Match Litematica PositionUtils: mirror first, then rotate.
        if ("LEFT_RIGHT".equals(mirror)) z = -z;
        else if ("FRONT_BACK".equals(mirror)) x = -x;

        return switch (rotation) {
            case "CLOCKWISE_90" -> new BlockPos(-z, y, x);
            case "COUNTERCLOCKWISE_90" -> new BlockPos(z, y, -x);
            case "CLOCKWISE_180" -> new BlockPos(-x, y, -z);
            default -> new BlockPos(x, y, z);
        };
    }

    private static float yawForFacing(String facing) {
        if (facing == null) return Float.NaN;
        int x = 0;
        int z = 0;
        switch (facing) {
            case "north" -> z = -1;
            case "south" -> z = 1;
            case "west" -> x = -1;
            case "east" -> x = 1;
            default -> { return Float.NaN; }
        }

        if ("LEFT_RIGHT".equals(mirror)) z = -z;
        else if ("FRONT_BACK".equals(mirror)) x = -x;

        int tx = x;
        int tz = z;
        switch (rotation) {
            case "CLOCKWISE_90" -> { tx = -z; tz = x; }
            case "COUNTERCLOCKWISE_90" -> { tx = z; tz = -x; }
            case "CLOCKWISE_180" -> { tx = -x; tz = -z; }
        }

        if (tz > 0) return 0.0F;
        if (tx < 0) return 90.0F;
        if (tz < 0) return 180.0F;
        if (tx > 0) return -90.0F;
        return Float.NaN;
    }

    private static int findBlockInHotbar(Minecraft client, Block expectedBlock) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() == expectedBlock) return slot;
        }
        return -1;
    }

    private static Support findSupport(Minecraft client, BlockPos target) {
        for (Direction side : Direction.values()) {
            BlockPos supportPos = target.relative(side);
            BlockState supportState = client.level.getBlockState(supportPos);
            if (supportState.isAir() || client.level.getBlockEntity(supportPos) != null) continue;

            Direction clickedFace = side.getOpposite();
            Vec3 hitLocation = Vec3.atCenterOf(supportPos).add(
                    clickedFace.getStepX() * 0.499,
                    clickedFace.getStepY() * 0.499,
                    clickedFace.getStepZ() * 0.499
            );
            return new Support(supportPos, clickedFace, hitLocation);
        }
        return null;
    }

    private record Support(BlockPos blockPos, Direction clickedFace, Vec3 hitLocation) {}
}
