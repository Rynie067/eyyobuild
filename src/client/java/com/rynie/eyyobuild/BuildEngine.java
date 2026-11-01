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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Automatic schematic builder.
 *
 * Auto movement is active only while a build session is running. On ground it follows
 * a bounded path made only of supported, walkable positions. If the player is already
 * flying, it steers in 3D while preserving the current flight ability. It never attempts
 * to enable flight or walk across unsupported air.
 */
public final class BuildEngine {
    private static final double REACH_SQUARED = 20.25;
    private static final int MAX_PATH_NODES = 16_000;
    private static final int MAX_PATH_RADIUS = 40;
    private static final int MAX_PATH_HEIGHT = 8;

    private static boolean active;
    private static String status = "Hazır.";
    private static List<SchematicBlock> blocks = List.of();
    private static BlockPos anchor;
    private static int cursor;
    private static String rotation = "NONE";
    private static String mirror = "NONE";
    private static String placementName = "";

    private static BlockPos pathTarget;
    private static List<BlockPos> path = List.of();
    private static int pathIndex;

    // Track only keys EYYOBUILD pressed. When inactive, never clear the player's own inputs.
    private static boolean ownsForward;
    private static boolean ownsJump;
    private static boolean ownsDescend;

    private BuildEngine() {}

    public static synchronized boolean isActive() {
        return active;
    }

    public static synchronized String status() {
        return status + (blocks.isEmpty() ? "" : "  " + Math.min(cursor, blocks.size()) + "/" + blocks.size());
    }

    public static synchronized void start(Minecraft client, SchematicSummary schematic) {
        releaseAutomationInput(client);
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
        resetPath();
        active = true;
        status = "Otomatik şematik başladı: " + anchor.toShortString() + ".";
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
        resetPath();
    }

    public static synchronized void tick(Minecraft client) {
        if (!active) {
            // Release only keys that EYYOBUILD itself pressed; normal player input is untouched.
            releaseAutomationInput(client);
            return;
        }
        if (client.player == null || client.level == null) {
            stop("Dünya kapandı; inşaat durduruldu.");
            releaseAutomationInput(client);
            return;
        }
        if (cursor >= blocks.size()) {
            stop("Şema tamamlandı!");
            releaseAutomationInput(client);
            return;
        }

        SchematicBlock schematicBlock = blocks.get(cursor);
        BlockPos offset = transform(schematicBlock.x(), schematicBlock.y(), schematicBlock.z());
        BlockPos target = anchor.offset(offset.getX(), offset.getY(), offset.getZ());

        var blockReference = BuiltInRegistries.BLOCK.get(Identifier.parse(schematicBlock.blockId()));
        if (blockReference.isEmpty()) {
            status = "Bilinmeyen blok: " + schematicBlock.blockId();
            active = false;
            releaseAutomationInput(client);
            return;
        }
        Block expectedBlock = blockReference.get().value();

        BlockState existing = client.level.getBlockState(target);
        if (existing.getBlock() == expectedBlock) {
            releaseAutomationInput(client);
            cursor++;
            resetPath();
            status = "Bloklar yerleştiriliyor...";
            return;
        }

        if (client.player.distanceToSqr(Vec3.atCenterOf(target)) > REACH_SQUARED) {
            moveTowardTarget(client, target);
            return;
        }

        // Within interaction reach: stop automated movement, but keep the build session active.
        releaseAutomationInput(client);
        resetPath();

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

    private static void moveTowardTarget(Minecraft client, BlockPos target) {
        if (client.player.getAbilities().flying) {
            moveWhileFlying(client, target);
            return;
        }

        BlockPos feet = client.player.blockPosition();
        if (!isWalkable(client, feet)) {
            releaseAutomationInput(client);
            status = "Güvenli zemin yok. Düşmemek için otomatik yürüme beklemede; uçuşunu aç veya sağlam zemine gel.";
            resetPath();
            return;
        }

        if (pathTarget == null || !pathTarget.equals(target) || path.isEmpty()) {
            pathTarget = target;
            path = findPath(client, feet, target);
            pathIndex = path.size() > 1 ? 1 : 0;
        }

        if (path.isEmpty() || pathIndex >= path.size()) {
            releaseAutomationInput(client);
            status = "Bu bloğa düşmeden ulaşan güvenli yürüyüş yolu bulunamadı.";
            return;
        }

        BlockPos next = path.get(pathIndex);
        double dx = next.getX() + 0.5 - client.player.getX();
        double dz = next.getZ() + 0.5 - client.player.getZ();

        if (Math.abs(dx) < 0.20 && Math.abs(dz) < 0.20
                && Math.abs(next.getY() - client.player.getY()) < 0.75) {
            pathIndex++;
            if (pathIndex >= path.size()) {
                releaseAutomationInput(client);
                status = "İnşaat alanına ulaşıldı.";
                return;
            }
            next = path.get(pathIndex);
            dx = next.getX() + 0.5 - client.player.getX();
            dz = next.getZ() + 0.5 - client.player.getZ();
        }

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        client.player.setYRot(yaw);
        setAutoForward(client, true);
        setAutoJump(client, next.getY() > feet.getY());
        setAutoDescend(client, false);
        status = "Otomatik yürüyor...";
    }

    private static void moveWhileFlying(Minecraft client, BlockPos target) {
        // Preserve an already-active creative/spectator flight; never toggle flight permission.
        double dx = target.getX() + 0.5 - client.player.getX();
        double dy = target.getY() + 0.5 - client.player.getY();
        double dz = target.getZ() + 0.5 - client.player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDistance > 0.35) {
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            client.player.setYRot(yaw);
        }

        setAutoForward(client, horizontalDistance > 0.45);
        setAutoJump(client, dy > 0.65);
        setAutoDescend(client, dy < -0.65);
        status = "Uçarak otomatik yaklaşıyor...";
    }

    private static List<BlockPos> findPath(Minecraft client, BlockPos start, BlockPos target) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);
        BlockPos goal = null;
        int examined = 0;

        while (!queue.isEmpty() && examined++ < MAX_PATH_NODES) {
            BlockPos current = queue.removeFirst();
            if (isBuildStandPosition(client, current, target)) {
                goal = current;
                break;
            }

            if (Math.abs(current.getX() - start.getX()) > MAX_PATH_RADIUS
                    || Math.abs(current.getZ() - start.getZ()) > MAX_PATH_RADIUS
                    || Math.abs(current.getY() - start.getY()) > MAX_PATH_HEIGHT) {
                continue;
            }

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int dy = 1; dy >= -1; dy--) {
                    BlockPos next = current.relative(direction).offset(0, dy, 0);
                    if (visited.contains(next) || !isWalkable(client, next)) continue;
                    visited.add(next);
                    previous.put(next, current);
                    queue.addLast(next);
                }
            }
        }

        if (goal == null) return List.of();
        ArrayList<BlockPos> result = new ArrayList<>();
        for (BlockPos cursor = goal; cursor != null; cursor = previous.get(cursor)) {
            result.add(cursor);
            if (cursor.equals(start)) break;
        }
        java.util.Collections.reverse(result);
        return result;
    }

    private static boolean isBuildStandPosition(Minecraft client, BlockPos feet, BlockPos target) {
        return isWalkable(client, feet)
                && Vec3.atCenterOf(feet).distanceToSqr(Vec3.atCenterOf(target)) <= REACH_SQUARED;
    }

    private static boolean isWalkable(Minecraft client, BlockPos feet) {
        BlockState feetState = client.level.getBlockState(feet);
        BlockState headState = client.level.getBlockState(feet.above());
        BlockState floorState = client.level.getBlockState(feet.below());
        return feetState.isAir() && headState.isAir() && !floorState.isAir()
                && !floorState.getFluidState().isLava();
    }

    private static void setAutoForward(Minecraft client, boolean pressed) {
        if (pressed && !ownsForward && !client.options.keyUp.isDown()) {
            client.options.keyUp.setDown(true);
            ownsForward = true;
        } else if (!pressed && ownsForward) {
            client.options.keyUp.setDown(false);
            ownsForward = false;
        }
    }

    private static void setAutoJump(Minecraft client, boolean pressed) {
        if (pressed && !ownsJump && !client.options.keyJump.isDown()) {
            client.options.keyJump.setDown(true);
            ownsJump = true;
        } else if (!pressed && ownsJump) {
            client.options.keyJump.setDown(false);
            ownsJump = false;
        }
    }

    private static void setAutoDescend(Minecraft client, boolean pressed) {
        if (pressed && !ownsDescend && !client.options.keyShift.isDown()) {
            client.options.keyShift.setDown(true);
            ownsDescend = true;
        } else if (!pressed && ownsDescend) {
            client.options.keyShift.setDown(false);
            ownsDescend = false;
        }
    }

    private static void releaseAutomationInput(Minecraft client) {
        if (client == null || client.options == null) {
            ownsForward = false;
            ownsJump = false;
            ownsDescend = false;
            return;
        }
        setAutoForward(client, false);
        setAutoJump(client, false);
        setAutoDescend(client, false);
    }

    private static void resetPath() {
        pathTarget = null;
        path = List.of();
        pathIndex = 0;
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
