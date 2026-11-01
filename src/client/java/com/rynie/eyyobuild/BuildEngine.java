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
 * Visible schematic build executor.
 *
 * It uses ordinary client interactions and movement keys. In multiplayer, the server remains
 * authoritative: protected areas, permissions, anti-cheat and rejected interactions are respected.
 * Navigation is deliberately bounded.
 */
public final class BuildEngine {
    private static final int MAX_PATH_NODES = 12_000;
    private static final int MAX_PATH_RADIUS = 28;

    private static boolean active;
    private static String status = "Hazır.";
    private static List<SchematicBlock> blocks = List.of();
    private static BlockPos anchor;
    private static int cursor;
    private static int attempts;
    private static BlockPos pathTarget;
    private static List<BlockPos> path = List.of();
    private static int pathIndex;

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

        blocks = new ArrayList<>(schematic.blocks());
        blocks.sort(Comparator.comparingInt(SchematicBlock::y)
                .thenComparingInt(SchematicBlock::z)
                .thenComparingInt(SchematicBlock::x));
        anchor = client.player.blockPosition().relative(client.player.getDirection());
        cursor = 0;
        attempts = 0;
        pathTarget = null;
        path = List.of();
        pathIndex = 0;
        active = true;
        status = "İnşaat başladı.";
    }

    public static synchronized void stop(String message) {
        active = false;
        status = message;
        blocks = List.of();
        anchor = null;
        cursor = 0;
        attempts = 0;
        pathTarget = null;
        path = List.of();
        pathIndex = 0;
    }

    public static synchronized void tick(Minecraft client) {
        if (!active) {
            releaseMovement(client);
            return;
        }
        if (client.player == null || client.level == null) {
            stop("Dünya kapandı; inşaat durduruldu.");
            releaseMovement(client);
            return;
        }

        releaseMovement(client);
        if (cursor >= blocks.size()) {
            stop("Şema tamamlandı!");
            return;
        }

        SchematicBlock schematicBlock = blocks.get(cursor);
        BlockPos target = anchor.offset(schematicBlock.x(), schematicBlock.y(), schematicBlock.z());
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
            attempts = 0;
            pathTarget = null;
            path = List.of();
            status = "Bloklar yerleştiriliyor.";
            return;
        }

        if (!existing.isAir()) {
            if (client.level.getBlockEntity(target) != null) {
                status = "Sandık/varil gibi blok güvenlik için korunuyor: " + target.toShortString();
                active = false;
                return;
            }
            if (client.player.distanceToSqr(Vec3.atCenterOf(target)) > 20.25) {
                moveNear(client, target);
                return;
            }
            boolean destroyed = client.gameMode.destroyBlock(target);
            status = destroyed ? "Yanlış blok kırılıyor..." : "Blok kırılamadı; işlem duraklatıldı.";
            if (!destroyed) active = false;
            attempts++;
            return;
        }

        int hotbarSlot = findBlockInHotbar(client, expectedBlock);
        if (hotbarSlot < 0) {
            status = "Eksik eşya: " + schematicBlock.blockId() + " (sıcak çubuk)";
            return;
        }
        client.player.getInventory().setSelectedSlot(hotbarSlot);

        if (client.player.distanceToSqr(Vec3.atCenterOf(target)) > 20.25) {
            moveNear(client, target);
            return;
        }

        Support support = findSupport(client, target);
        if (support == null) {
            status = "Destek blok bekleniyor: " + target.toShortString();
            attempts++;
            if (attempts > 200) {
                status = "Bu konuma yerleştirmek için destek blok veya erişim gerekiyor.";
                attempts = 0;
            }
            return;
        }

        orientForPlacement(client, schematicBlock);
        client.options.keyShift.setDown(true);
        BlockHitResult hit = new BlockHitResult(
                support.hitLocation(), support.clickedFace(), support.blockPos(), false
        );
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        status = "Blok yerleştiriliyor...";
        attempts++;
    }

    private static int findBlockInHotbar(Minecraft client, Block expectedBlock) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() == expectedBlock) {
                return slot;
            }
        }
        return -1;
    }

    private static Support findSupport(Minecraft client, BlockPos target) {
        for (Direction side : Direction.values()) {
            BlockPos supportPos = target.relative(side);
            BlockState supportState = client.level.getBlockState(supportPos);
            if (!supportState.isAir()) {
                Direction clickedFace = side.getOpposite();
                Vec3 hitLocation = Vec3.atCenterOf(supportPos).add(
                        clickedFace.getStepX() * 0.499,
                        clickedFace.getStepY() * 0.499,
                        clickedFace.getStepZ() * 0.499
                );
                return new Support(supportPos, clickedFace, hitLocation);
            }
        }
        return null;
    }

    private static void moveNear(Minecraft client, BlockPos target) {
        BlockPos feet = client.player.blockPosition();
        if (client.player.distanceToSqr(Vec3.atCenterOf(target)) <= 20.25) {
            return;
        }

        if (pathTarget == null || !pathTarget.equals(target) || path.isEmpty()) {
            pathTarget = target;
            path = findPath(client, feet, target);
            pathIndex = Math.min(1, Math.max(0, path.size() - 1));
        }

        if (path.isEmpty() || pathIndex >= path.size()) {
            status = "Güvenli yürüyüş yolu bulunamadı.";
            return;
        }

        BlockPos next = path.get(pathIndex);
        double dx = (next.getX() + 0.5) - client.player.getX();
        double dz = (next.getZ() + 0.5) - client.player.getZ();
        if (Math.abs(dx) < 0.18 && Math.abs(dz) < 0.18
                && Math.abs(next.getY() - client.player.getY()) < 0.8) {
            pathIndex++;
            if (pathIndex >= path.size()) {
                path = List.of();
                status = "İnşaat alanına ulaşıldı.";
                return;
            }
            next = path.get(pathIndex);
            dx = (next.getX() + 0.5) - client.player.getX();
            dz = (next.getZ() + 0.5) - client.player.getZ();
        }

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        client.player.setYRot(yaw);
        client.options.keyUp.setDown(true);
        if (next.getY() > feet.getY()) {
            client.options.keyJump.setDown(true);
        }
        status = "Otomatik yürüyor...";
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
                    || Math.abs(current.getY() - start.getY()) > 5) {
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
        if (!isWalkable(client, feet)) return false;
        return Vec3.atCenterOf(feet).distanceToSqr(Vec3.atCenterOf(target)) <= 20.25;
    }

    private static boolean isWalkable(Minecraft client, BlockPos feet) {
        return client.level.getBlockState(feet).isAir()
                && client.level.getBlockState(feet.above()).isAir()
                && !client.level.getBlockState(feet.below()).isAir();
    }

    private static void orientForPlacement(Minecraft client, SchematicBlock block) {
        String facing = block.properties().get("facing");
        if (facing == null) return;
        float yaw = switch (facing) {
            case "south" -> 0.0F;
            case "west" -> 90.0F;
            case "north" -> 180.0F;
            case "east" -> -90.0F;
            default -> client.player.getYRot();
        };
        client.player.setYRot(yaw);
    }

    private static void releaseMovement(Minecraft client) {
        if (client == null || client.options == null) return;
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keyShift.setDown(false);
    }

    private record Support(BlockPos blockPos, Direction clickedFace, Vec3 hitLocation) {}
}
