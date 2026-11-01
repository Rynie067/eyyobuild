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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
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
 * Creative players are automatically switched into flight while a build is active.
 * Survival/adventure players use safe, ground-based pathfinding and never receive flight.
 * Only keys and abilities changed by EYYOBUILD are restored when the session stops.
 */
public final class BuildEngine {
    private static final double REACH_SQUARED = 20.25;
    private static final int MAX_PATH_NODES = 24_000;
    private static final int MAX_PATH_RADIUS = 40;
    private static final int MAX_PATH_HEIGHT = 24;

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

    // Track only controls changed by EYYOBUILD.
    private static boolean ownsForward;
    private static boolean ownsJump;
    private static boolean ownsDescend;
    private static boolean ownsFlight;
    private static int missingBlockSkips;
    private static int actionCooldownTicks;

    private BuildEngine() {}

    public static synchronized boolean isActive() {
        return active;
    }

    public static synchronized String status() {
        String progress = blocks.isEmpty() ? "" : "  " + Math.min(cursor, blocks.size()) + "/" + blocks.size();
        String skipped = missingBlockSkips == 0 ? "" : "  |  atlanan: " + missingBlockSkips;
        return status + progress + skipped;
    }

    public static synchronized void start(Minecraft client, SchematicSummary schematic) {
        releaseAutomationInput(client);
        releaseAutomationFlight(client);
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
        anchor = placement.origin();
        rotation = placement.rotation();
        mirror = placement.mirror();
        placementName = placement.placementName();
        cursor = 0;
        missingBlockSkips = 0;
        actionCooldownTicks = 0;

        // Keep the natural bottom-up build order, but start each layer at the
        // position nearest to the player's location when the build is started.
        // This avoids always beginning from the schematic's same X/Z corner.
        final double startX = client.player.getX();
        final double startZ = client.player.getZ();
        blocks.sort(Comparator.comparingInt(SchematicBlock::y)
                .thenComparingDouble((SchematicBlock block) -> {
                    BlockPos relative = transform(block.x(), block.y(), block.z());
                    double dx = anchor.getX() + relative.getX() + 0.5 - startX;
                    double dz = anchor.getZ() + relative.getZ() + 0.5 - startZ;
                    return dx * dx + dz * dz;
                })
                .thenComparingInt(SchematicBlock::z)
                .thenComparingInt(SchematicBlock::x));
        resetPath();

        // Creative mode is allowed to fly. Enable it for this build session so high
        // schematics can be reached from the UI without requiring a manual double-space.
        enableCreativeFlightIfAllowed(client);
        active = true;
        status = "Otomatik şematik başladı: " + anchor.toShortString() + ".";
    }

    public static synchronized void stop(String message) {
        active = false;
        status = message;
        blocks = List.of();
        anchor = null;
        cursor = 0;
        actionCooldownTicks = 0;
        rotation = "NONE";
        mirror = "NONE";
        placementName = "";
        resetPath();
    }

    public static synchronized void tick(Minecraft client) {
        if (!active) {
            releaseAutomationInput(client);
            releaseAutomationFlight(client);
            return;
        }
        if (client.player == null || client.level == null) {
            stop("Dünya kapandı; inşaat durduruldu.");
            releaseAutomationInput(client);
            releaseAutomationFlight(client);
            return;
        }

        // If the server grants flight (creative/spectator), keep the build session
        // using it. Survival never has mayfly and therefore stays ground-based.
        enableCreativeFlightIfAllowed(client);

        // Count down continuously so walking/flying is not slowed by the setting.
        // The cooldown is enforced only when an actual block interaction is due.
        boolean actionCoolingDown = actionCooldownTicks > 0;
        if (actionCoolingDown) actionCooldownTicks--;

        if (cursor >= blocks.size()) {
            String completion = missingBlockSkips == 0
                    ? "Şema tamamlandı!"
                    : "Şema tamamlandı. Envanterinde olmayan " + missingBlockSkips + " blok atlandı.";
            stop(completion);
            releaseAutomationInput(client);
            return;
        }

        SchematicBlock schematicBlock = blocks.get(cursor);
        BlockPos offset = transform(schematicBlock.x(), schematicBlock.y(), schematicBlock.z());
        BlockPos target = anchor.offset(offset.getX(), offset.getY(), offset.getZ());

        var blockReference = BuiltInRegistries.BLOCK.get(Identifier.parse(schematicBlock.blockId()));
        if (blockReference.isEmpty()) {
            missingBlockSkips++;
            cursor++;
            resetPath();
            status = "Oyunda bulunmayan blok atlandı: " + schematicBlock.blockId();
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

        Support support = null;
        if (existing.isAir()) {
            if (!hasAdjacentSupport(client, target)) {
                releaseAutomationInput(client);
                status = "Havaya blok koyma kapalı: " + target.toShortString()
                        + " için önce komşu sağlam bir destek bloğu gerekiyor.";
                return;
            }

            support = findSupport(client, target, client.player.getEyePosition());
            if (support == null) {
                moveTowardTarget(client, target);
                status = "Görüş hattında ve normal menzilde destek aranıyor...";
                return;
            }
        } else if (client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) > REACH_SQUARED) {
            moveTowardTarget(client, target);
            return;
        }

        // Within normal interaction reach: stop automated movement, but keep the build session active.
        releaseAutomationInput(client);
        resetPath();

        if (actionCoolingDown) {
            status = "Blok işlemi bekleniyor...";
            return;
        }

        if (!existing.isAir()) {
            if (client.level.getBlockEntity(target) != null) {
                status = "Blok varlığı korunuyor: " + target.toShortString();
                active = false;
                return;
            }

            if (!aimAt(client, Vec3.atCenterOf(target))) {
                status = "Kırılacak bloğa bakılıyor...";
                return;
            }

            HitResult aimedResult = client.player.pick(4.5D, 1.0F, false);
            if (!(aimedResult instanceof BlockHitResult aimedBlock)
                    || aimedBlock.getType() != HitResult.Type.BLOCK
                    || !aimedBlock.getBlockPos().equals(target)) {
                status = "Blok doğrudan nişangâhta değil; kırma yapılmadı.";
                return;
            }

            boolean destroyed = client.gameMode.destroyBlock(target);
            if (destroyed) actionCooldownTicks = BuildSettings.getActionDelayTicks();
            status = destroyed
                    ? "Yanlış blok normal menzilde kırılıyor: " + target.toShortString()
                    : "Blok kırılamadı; sunucu işlemi reddetmiş olabilir.";
            if (!destroyed) active = false;
            return;
        }

        int hotbarSlot = findAndSelectBlockInInventory(client, expectedBlock);
        if (hotbarSlot < 0) {
            // Missing material must not freeze the whole schematic at one position.
            // Count this position as skipped and continue with the next block.
            missingBlockSkips++;
            cursor++;
            resetPath();
            status = "Envanterde yok, blok atlandı: " + schematicBlock.blockId();
            return;
        }

        client.player.getInventory().setSelectedSlot(hotbarSlot);

        if (!aimAt(client, support.hitLocation())) {
            status = "Destek bloğuna bakılıyor...";
            return;
        }

        // Use the player's real crosshair ray. No synthetic hit result or air placement.
        HitResult aimedResult = client.player.pick(4.5D, 1.0F, false);
        if (!(aimedResult instanceof BlockHitResult aimedBlock)
                || aimedBlock.getType() != HitResult.Type.BLOCK
                || !aimedBlock.getBlockPos().equals(support.blockPos())
                || aimedBlock.getDirection() != support.clickedFace()) {
            status = "Destek bloğu nişangâhta değil; yerleştirme yapılmadı.";
            return;
        }

        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, aimedBlock);
        actionCooldownTicks = BuildSettings.getActionDelayTicks();
        status = "Bloka bakarak normal yerleştirme: " + target.toShortString();
    }

    /**
     * Rotates the real player view toward a world-space point.
     * The view remains on the target so the subsequent vanilla ray trace and
     * normal block interaction agree about what is being clicked.
     */
    private static boolean aimAt(Minecraft client, Vec3 point) {
        Vec3 eye = client.player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        targetPitch = Math.max(-90.0F, Math.min(90.0F, targetPitch));

        float nextYaw = stepAngle(client.player.getYRot(), targetYaw, 35.0F);
        float nextPitch = stepLinear(client.player.getXRot(), targetPitch, 25.0F);
        client.player.setYRot(nextYaw);
        client.player.setXRot(nextPitch);

        return Math.abs(wrapDegrees(targetYaw - nextYaw)) < 0.01F
                && Math.abs(targetPitch - nextPitch) < 0.01F;
    }

    private static float stepAngle(float current, float target, float maxStep) {
        float delta = wrapDegrees(target - current);
        return current + Math.max(-maxStep, Math.min(maxStep, delta));
    }

    private static float stepLinear(float current, float target, float maxStep) {
        float delta = target - current;
        return current + Math.max(-maxStep, Math.min(maxStep, delta));
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    private static void enableCreativeFlightIfAllowed(Minecraft client) {
        if (client.player == null) return;
        var abilities = client.player.getAbilities();
        if (!abilities.mayfly || abilities.flying) return;

        abilities.flying = true;
        ownsFlight = true;
        notifyAbilitiesChanged(client);
    }

    private static void releaseAutomationFlight(Minecraft client) {
        if (!ownsFlight) return;
        if (client == null || client.player == null) {
            ownsFlight = false;
            return;
        }

        var abilities = client.player.getAbilities();
        if (abilities.mayfly && abilities.flying) {
            abilities.flying = false;
            notifyAbilitiesChanged(client);
        }
        ownsFlight = false;
    }

    /**
     * LocalPlayer exposes onUpdateAbilities in Minecraft 26.2. Reflection keeps
     * this optional sync call isolated from mappings while still sending the
     * normal vanilla abilities packet when creative flight is toggled.
     */
    private static void notifyAbilitiesChanged(Minecraft client) {
        try {
            Method method = client.player.getClass().getMethod("onUpdateAbilities");
            method.invoke(client.player);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // If a mapping/provider omits the hook, the server may correct the local
            // state; the builder will safely fall back to ground movement.
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
            status = "Güvenli zemin yok. Düşmemek için bekliyor; sağlam zemine gel.";
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
            status = "Bu bloğa güvenli yürüyüş yolu bulunamadı. Hayatta kalmada uçuş kullanılamaz.";
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
        if (!isWalkable(client, feet)) return false;
        Vec3 eye = new Vec3(feet.getX() + 0.5D, feet.getY() + 1.62D, feet.getZ() + 0.5D);
        return findSupport(client, target, eye) != null;
    }

    private static boolean isWalkable(Minecraft client, BlockPos feet) {
        BlockState feetState = client.level.getBlockState(feet);
        BlockState headState = client.level.getBlockState(feet.above());
        BlockPos floorPos = feet.below();
        BlockState floorState = client.level.getBlockState(floorPos);

        // Collision shapes rather than isAir() lets the player pass through
        // grass, flowers, snow layers and other non-solid decorative blocks.
        return feetState.getCollisionShape(client.level, feet).isEmpty()
                && headState.getCollisionShape(client.level, feet.above()).isEmpty()
                && feetState.getFluidState().isEmpty()
                && headState.getFluidState().isEmpty()
                && !floorState.getCollisionShape(client.level, floorPos).isEmpty()
                && floorState.getFluidState().isEmpty();
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

    /**
     * Finds a required block in the complete 36-slot player inventory.
     * If it is in the main inventory, swap it into an empty hotbar slot (or
     * the currently selected slot when the hotbar is full), then select it.
     * Returns the hotbar slot, or -1 when the player does not have the block.
     */
    private static int findAndSelectBlockInInventory(Minecraft client, Block expectedBlock) {
        var inventory = client.player.getInventory();

        // Prefer a block stack that's already in the hotbar.
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() == expectedBlock) {
                inventory.setSelectedSlot(slot);
                return slot;
            }
        }

        // PlayerInventory main slots are 9..35. Move the matching stack into
        // a free hotbar slot so the normal placement interaction can use it.
        for (int inventorySlot = 9; inventorySlot < 36; inventorySlot++) {
            ItemStack stack = inventory.getItem(inventorySlot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)
                    || blockItem.getBlock() != expectedBlock) {
                continue;
            }

            int destination = -1;
            for (int hotbarSlot = 0; hotbarSlot < 9; hotbarSlot++) {
                if (inventory.getItem(hotbarSlot).isEmpty()) {
                    destination = hotbarSlot;
                    break;
                }
            }

            // If every hotbar slot is occupied, use the selected slot. The
            // displaced item is swapped back into the source inventory slot.
            if (destination < 0) destination = inventory.getSelectedSlot();

            inventory.setSelectedSlot(destination);
            inventory.pickSlot(inventorySlot);

            ItemStack selected = inventory.getItem(destination);
            if (!selected.isEmpty() && selected.getItem() instanceof BlockItem selectedBlock
                    && selectedBlock.getBlock() == expectedBlock) {
                return destination;
            }
            return -1;
        }

        return -1;
    }

    private static boolean hasAdjacentSupport(Minecraft client, BlockPos target) {
        for (Direction side : Direction.values()) {
            BlockPos supportPos = target.relative(side);
            BlockState supportState = client.level.getBlockState(supportPos);
            if (!supportState.isAir()
                    && client.level.getBlockEntity(supportPos) == null
                    && !supportState.getCollisionShape(client.level, supportPos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds an adjacent solid face that the player can actually see and reach.
     * The ray must hit that exact face; walls, corners and empty space do not count.
     */
    private static Support findSupport(Minecraft client, BlockPos target, Vec3 eye) {
        Support best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Direction side : Direction.values()) {
            BlockPos supportPos = target.relative(side);
            BlockState supportState = client.level.getBlockState(supportPos);
            if (supportState.isAir() || client.level.getBlockEntity(supportPos) != null) continue;

            var shape = supportState.getCollisionShape(client.level, supportPos);
            if (shape.isEmpty()) continue;

            Direction clickedFace = side.getOpposite();
            AABB bounds = shape.bounds();
            double x = supportPos.getX() + (bounds.minX + bounds.maxX) * 0.5D;
            double y = supportPos.getY() + (bounds.minY + bounds.maxY) * 0.5D;
            double z = supportPos.getZ() + (bounds.minZ + bounds.maxZ) * 0.5D;

            // Aim at the middle of the actual collision-shape face (works for slabs too).
            switch (clickedFace) {
                case DOWN -> y = supportPos.getY() + bounds.minY + 0.001D;
                case UP -> y = supportPos.getY() + bounds.maxY - 0.001D;
                case NORTH -> z = supportPos.getZ() + bounds.minZ + 0.001D;
                case SOUTH -> z = supportPos.getZ() + bounds.maxZ - 0.001D;
                case WEST -> x = supportPos.getX() + bounds.minX + 0.001D;
                case EAST -> x = supportPos.getX() + bounds.maxX - 0.001D;
            }

            Vec3 hitLocation = new Vec3(x, y, z);
            double distance = eye.distanceToSqr(hitLocation);
            if (distance > REACH_SQUARED || distance >= bestDistance) continue;

            BlockHitResult sight = client.level.clip(new ClipContext(
                    eye, hitLocation, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player
            ));
            if (sight.getType() != HitResult.Type.BLOCK
                    || !sight.getBlockPos().equals(supportPos)
                    || sight.getDirection() != clickedFace) {
                continue;
            }

            bestDistance = distance;
            best = new Support(supportPos, clickedFace, hitLocation);
        }
        return best;
    }

    private record Support(BlockPos blockPos, Direction clickedFace, Vec3 hitLocation) {}
}
