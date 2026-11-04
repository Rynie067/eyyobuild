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
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.FallingBlock;
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
    private static BlockPos pendingActionPos;
    private static Block pendingActionBlock;
    private static BlockPos pendingActionWalkTarget;
    private static int pendingActionTicks;
    private static BlockPos bridgeWalkTarget;

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
        clearPendingAction();
        bridgeWalkTarget = null;
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

        // A placement is not considered complete until the client receives the
        // resulting block state. This prevents repeated clicks on one unconfirmed
        // position, which looked like the builder had frozen after a few blocks.
        if (pendingActionPos != null) {
            if (pendingActionBlock == null
                    ? client.level.getBlockState(pendingActionPos).isAir()
                    : client.level.getBlockState(pendingActionPos).getBlock() == pendingActionBlock) {
                BlockPos walkTarget = pendingActionWalkTarget;
                clearPendingAction();
                setAutoDescend(client, false);
                if (walkTarget != null) {
                    bridgeWalkTarget = walkTarget;
                    resetPath();
                }
            } else if (++pendingActionTicks >= 40) {
                String failedPosition = pendingActionPos.toShortString();
                stop("Sunucu yerleştirmeyi onaylamadı: " + failedPosition
                        + ". İşlem reddedildi veya blok güncellemesi gelmedi; otomasyon durduruldu.");
                releaseAutomationInput(client);
                releaseAutomationFlight(client);
                return;
            } else {
                status = pendingActionWalkTarget != null
                        ? "Destek bloğunun sunucu onayı bekleniyor..."
                        : "Yerleştirilen bloğun sunucu onayı bekleniyor...";
                return;
            }
        }

        if (bridgeWalkTarget != null && moveOntoBridge(client)) return;

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
        boolean useAirPlace = false;
        if (existing.isAir()) {
            if (!hasAdjacentSupport(client, target)) {
                releaseAutomationInput(client);

                if (BuildSettings.isAirPlaceEnabled()) {
                    // Air Place uses a normal interaction packet aimed at the target
                    // position. The server still decides whether that placement is valid.
                    if (client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) > REACH_SQUARED) {
                        moveTowardTarget(client, target);
                        return;
                    }
                    useAirPlace = true;
                } else {
                    if (actionCoolingDown) {
                        status = "Destek yolu için yerleştirme aralığı bekleniyor...";
                        return;
                    }

                    // First try a normal temporary foundation immediately beneath the
                    // schematic block. If the player is still too far away, extend a
                    // crouched horizontal bridge one supported block at a time.
                    if (tryPlaceTargetFoundation(client, target)) return;
                    if (tryBuildBridgeStep(client, target)) return;

                    status = "Güvenli destek yolu kurulamıyor. Şemada kullanılmayan tam bloklardan "
                            + "bir miktar envanterinde bulundur ve aynı seviyede sağlam zeminde başla.";
                    return;
                }
            }

            if (!useAirPlace) {
                support = findSupport(client, target, client.player.getEyePosition());
                if (support == null) {
                    if (BuildSettings.isAirPlaceEnabled()
                            && client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) <= REACH_SQUARED) {
                        useAirPlace = true;
                    } else {
                        moveTowardTarget(client, target);
                        return;
                    }
                }
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

        BlockHitResult placementHit;
        if (useAirPlace) {
            if (!aimAt(client, Vec3.atCenterOf(target))) {
                status = "Air Place için hedef bloğa bakılıyor...";
                return;
            }

            // The packet targets the air block position itself. Servers may reject
            // this interaction; normal range/build-height/server checks still apply.
            placementHit = new BlockHitResult(
                    Vec3.atCenterOf(target), Direction.UP, target, false
            );
        } else {
            if (!aimAt(client, support.hitLocation())) {
                status = "Destek bloğuna bakılıyor...";
                return;
            }

            // Normal mode requires the real crosshair to hit the adjacent support face.
            HitResult aimedResult = client.player.pick(4.5D, 1.0F, false);
            if (!(aimedResult instanceof BlockHitResult aimedBlock)
                    || aimedBlock.getType() != HitResult.Type.BLOCK
                    || !aimedBlock.getBlockPos().equals(support.blockPos())
                    || aimedBlock.getDirection() != support.clickedFace()) {
                status = "Destek bloğu nişangâhta değil; yerleştirme yapılmadı.";
                return;
            }
            placementHit = aimedBlock;
        }

        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, placementHit);
        actionCooldownTicks = BuildSettings.getActionDelayTicks();
        pendingActionPos = target;
        pendingActionBlock = expectedBlock;
        pendingActionWalkTarget = null;
        pendingActionTicks = 0;
        status = (useAirPlace ? "Air Place denemesi: " : "Bloka bakarak normal yerleştirme: ")
                + target.toShortString();
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

        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();
        boolean alreadyAimed = Math.abs(wrapDegrees(targetYaw - currentYaw)) < 0.5F
                && Math.abs(targetPitch - currentPitch) < 0.5F;
        if (alreadyAimed) return true;

        // Rotate now, but never click in the same tick as a rotation change.
        // The next client tick sends the updated look before the interaction packet.
        client.player.setYRot(stepAngle(currentYaw, targetYaw, 35.0F));
        client.player.setXRot(stepLinear(currentPitch, targetPitch, 25.0F));
        return false;
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
            // No pre-existing floor route: extend a normal block bridge from the
            // current supported tile. Each step is placed against a visible face.
            if (tryBuildBridgeStep(client, target)) return;
            releaseAutomationInput(client);
            status = "Bu hedefe güvenli yürüyüş yolu yok. Destek için envanterinde "
                    + "şemada kullanılmayan tam blok bulundur.";
            return;
        }

        BlockPos next = path.get(pathIndex);
        if (clearPathObstruction(client, next)) return;
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

    /**
     * Clears breakable blocks in the next path cell before walking into it.
     * Block entities and unbreakable blocks are never touched. A successful
     * break waits for the server's air-state confirmation before continuing.
     */
    private static Vec3 centerOf(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private static boolean clearPathObstruction(Minecraft client, BlockPos destination) {
        for (BlockPos obstruction : List.of(destination, destination.above())) {
            if (actionCooldownTicks > 0) {
                status = "Yol engeli için kırma aralığı bekleniyor...";
                return true;
            }
            BlockState state = client.level.getBlockState(obstruction);
            if (state.getCollisionShape(client.level, obstruction).isEmpty()) continue;

            if (client.level.getBlockEntity(obstruction) != null
                    || state.getDestroySpeed(client.level, obstruction) < 0.0F) {
                releaseAutomationInput(client);
                resetPath();
                status = "Yol üzerindeki engel kırılamıyor veya blok varlığı; güvenli rota yeniden aranıyor.";
                return true;
            }

            if (client.player.getEyePosition().distanceToSqr(centerOf(obstruction)) > REACH_SQUARED) {
                return false;
            }

            if (!aimAt(client, centerOf(obstruction))) {
                releaseAutomationInput(client);
                status = "Yol üzerindeki engel için nişan alınıyor...";
                return true;
            }

            HitResult aimedResult = client.player.pick(4.5D, 1.0F, false);
            if (!(aimedResult instanceof BlockHitResult aimedBlock)
                    || aimedBlock.getType() != HitResult.Type.BLOCK
                    || !aimedBlock.getBlockPos().equals(obstruction)) {
                releaseAutomationInput(client);
                status = "Engel gerçek nişangâhta değil; kırma yapılmadı.";
                return true;
            }

            boolean destroyed = client.gameMode.destroyBlock(obstruction);
            if (!destroyed) {
                releaseAutomationInput(client);
                active = false;
                status = "Yol engeli kırılamadı; sunucu işlemi reddetti. Otomasyon durduruldu.";
                return true;
            }

            releaseAutomationInput(client);
            actionCooldownTicks = BuildSettings.getActionDelayTicks();
            pendingActionPos = obstruction;
            pendingActionBlock = null; // null means wait for the server to confirm air.
            pendingActionWalkTarget = null;
            pendingActionTicks = 0;
            status = "Yol açılıyor: " + obstruction.toShortString();
            return true;
        }
        return false;
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
        return NavigationPlanner.findPath(
                client,
                start,
                target,
                position -> isBuildStandPosition(client, position, target),
                MAX_PATH_NODES,
                MAX_PATH_RADIUS,
                MAX_PATH_HEIGHT
        );
    }

    private static boolean isBuildStandPosition(Minecraft client, BlockPos feet, BlockPos target) {
        if (!isWalkable(client, feet)) return false;
        Vec3 eye = new Vec3(feet.getX() + 0.5D, feet.getY() + 1.62D, feet.getZ() + 0.5D);
        if (BuildSettings.isAirPlaceEnabled()) {
            return eye.distanceToSqr(Vec3.atCenterOf(target)) <= REACH_SQUARED;
        }
        return findSupport(client, target, eye) != null;
    }

    private static boolean isWalkable(Minecraft client, BlockPos feet) {
        return NavigationPlanner.isWalkable(client, feet);
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

    /**
     * Walks onto the last acknowledged bridge tile using ordinary forward input.
     * The next bridge segment is not placed until the player has reached this tile.
     */
    private static boolean moveOntoBridge(Minecraft client) {
        BlockPos destination = bridgeWalkTarget;
        if (destination == null) return false;

        if (!isWalkable(client, destination)) {
            releaseAutomationInput(client);
            status = "Destek yolu sunucuda görünmüyor; güvenli hareket durduruldu.";
            bridgeWalkTarget = null;
            return false;
        }

        double dx = destination.getX() + 0.5D - client.player.getX();
        double dz = destination.getZ() + 0.5D - client.player.getZ();
        double dy = destination.getY() + 0.5D - client.player.getY();

        if (Math.abs(dx) < 0.18D && Math.abs(dz) < 0.18D && Math.abs(dy) < 0.75D) {
            bridgeWalkTarget = null;
            releaseAutomationInput(client);
            resetPath();
            return false;
        }

        client.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        setAutoDescend(client, false);
        setAutoForward(client, true);
        setAutoJump(client, destination.getY() > client.player.blockPosition().getY());
        status = "Eğilerek kurulan destek yoluna ilerliyor...";
        return true;
    }

    /**
     * Creates a one-block-high horizontal bridge from the floor tile under the
     * player. The block is placed only on the real, visible side face of that tile.
     */
    private static boolean tryBuildBridgeStep(Minecraft client, BlockPos schematicTarget) {
        if (client.player.getAbilities().flying) return false;

        BlockPos feet = client.player.blockPosition();
        if (!isWalkable(client, feet)) return false;

        BlockPos floor = feet.below();
        double currentDistance = horizontalDistance(client.player.getX(), client.player.getZ(), schematicTarget);
        Direction bestDirection = null;
        BlockPos bestPlacement = null;
        BlockPos bestWalkTarget = null;
        Support bestSupport = null;
        double bestDistance = currentDistance;

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos placement = floor.relative(direction);
            if (!client.level.getBlockState(placement).isAir()) continue;

            BlockPos nextFeet = placement.above();
            BlockState feetState = client.level.getBlockState(nextFeet);
            BlockState headState = client.level.getBlockState(nextFeet.above());
            if (!feetState.getCollisionShape(client.level, nextFeet).isEmpty()
                    || !headState.getCollisionShape(client.level, nextFeet.above()).isEmpty()
                    || !feetState.getFluidState().isEmpty()
                    || !headState.getFluidState().isEmpty()) {
                continue;
            }

            double nextDistance = horizontalDistance(nextFeet.getX() + 0.5D,
                    nextFeet.getZ() + 0.5D, schematicTarget);
            if (nextDistance >= bestDistance - 0.10D) continue;

            Support support = findSupport(client, placement, client.player.getEyePosition());
            if (support == null || !support.blockPos().equals(floor)
                    || support.clickedFace() != direction) {
                continue;
            }

            bestDistance = nextDistance;
            bestDirection = direction;
            bestPlacement = placement;
            bestWalkTarget = nextFeet;
            bestSupport = support;
        }

        if (bestDirection == null) return false;
        return placeSupportBlock(client, bestPlacement, bestSupport, bestWalkTarget,
                "Eğilerek destek yolu döşeniyor...");
    }

    /**
     * For the first block of a floating schematic section, try placing one spare
     * support block directly underneath it, using an adjacent visible block face.
     */
    private static boolean tryPlaceTargetFoundation(Minecraft client, BlockPos target) {
        BlockPos foundation = target.below();
        if (!client.level.getBlockState(foundation).isAir()) return false;

        Support support = findSupport(client, foundation, client.player.getEyePosition());
        if (support == null) return false;

        return placeSupportBlock(client, foundation, support, null,
                "Şema bloğunun altına geçici destek yerleştiriliyor...");
    }

    /**
     * Uses only a full-cube block stack that is surplus to the remaining schematic.
     * It avoids falling blocks and block-entity blocks. No required schematic stack
     * is consumed for scaffolding.
     */
    private static int findAndSelectSurplusSupportBlock(Minecraft client) {
        Map<String, Integer> needed = new HashMap<>();
        for (int i = cursor; i < blocks.size(); i++) {
            needed.merge(blocks.get(i).blockId(), 1, Integer::sum);
        }

        Map<String, Integer> available = new HashMap<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;
            String id = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
            available.merge(id, stack.getCount(), Integer::sum);
        }

        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;

            Block block = blockItem.getBlock();
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            if (available.getOrDefault(id, 0) <= needed.getOrDefault(id, 0)) continue;
            if (block instanceof FallingBlock || block instanceof BaseEntityBlock) continue;

            String path = id.substring(id.indexOf(':') + 1);
            if (path.contains("slab") || path.contains("stairs") || path.contains("wall")
                    || path.contains("fence") || path.contains("pane") || path.contains("door")
                    || path.contains("trapdoor") || path.contains("carpet") || path.contains("torch")
                    || path.contains("rail") || path.contains("button") || path.contains("pressure_plate")) {
                continue;
            }

            BlockState state = block.defaultBlockState();
            if (!state.getFluidState().isEmpty()) continue;
            AABB bounds = state.getCollisionShape(client.level, BlockPos.ZERO).bounds();
            if (bounds.minX > 0.01D || bounds.minY > 0.01D || bounds.minZ > 0.01D
                    || bounds.maxX < 0.99D || bounds.maxY < 0.99D || bounds.maxZ < 0.99D) {
                continue;
            }

            return selectBlockFromInventory(client, block);
        }
        return -1;
    }

    private static int selectBlockFromInventory(Minecraft client, Block expectedBlock) {
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() == expectedBlock) {
                inventory.setSelectedSlot(slot);
                return slot;
            }
        }

        for (int inventorySlot = 9; inventorySlot < 36; inventorySlot++) {
            ItemStack stack = inventory.getItem(inventorySlot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)
                    || blockItem.getBlock() != expectedBlock) continue;

            int destination = -1;
            for (int hotbarSlot = 0; hotbarSlot < 9; hotbarSlot++) {
                if (inventory.getItem(hotbarSlot).isEmpty()) {
                    destination = hotbarSlot;
                    break;
                }
            }
            if (destination < 0) destination = inventory.getSelectedSlot();

            inventory.setSelectedSlot(destination);
            inventory.pickSlot(inventorySlot);
            ItemStack selected = inventory.getItem(destination);
            if (!selected.isEmpty() && selected.getItem() instanceof BlockItem selectedBlock
                    && selectedBlock.getBlock() == expectedBlock) return destination;
            return -1;
        }
        return -1;
    }

    private static boolean placeSupportBlock(Minecraft client, BlockPos placePos, Support support,
                                             BlockPos walkTarget, String actionMessage) {
        int slot = findAndSelectSurplusSupportBlock(client);
        if (slot < 0) {
            status = "Destek yolu için şemada kullanılmayan tam blok yok. Envantere yedek blok ekle.";
            return false;
        }

        ItemStack selected = client.player.getInventory().getItem(slot);
        if (selected.isEmpty() || !(selected.getItem() instanceof BlockItem blockItem)) return false;
        Block supportBlock = blockItem.getBlock();
        client.player.getInventory().setSelectedSlot(slot);

        // Sneak at the edge so forward movement cannot step off the current tile.
        setAutoForward(client, false);
        setAutoJump(client, false);
        setAutoDescend(client, true);

        if (!aimAt(client, support.hitLocation())) {
            status = actionMessage + " (yüzeye dönüyor)";
            return true;
        }

        HitResult result = client.player.pick(4.5D, 1.0F, false);
        if (!(result instanceof BlockHitResult aimedBlock)
                || aimedBlock.getType() != HitResult.Type.BLOCK
                || !aimedBlock.getBlockPos().equals(support.blockPos())
                || aimedBlock.getDirection() != support.clickedFace()) {
            status = actionMessage + " (gerçek nişangâh kontrol ediliyor)";
            return true;
        }

        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, aimedBlock);
        actionCooldownTicks = BuildSettings.getActionDelayTicks();
        pendingActionPos = placePos;
        pendingActionBlock = supportBlock;
        pendingActionWalkTarget = walkTarget;
        pendingActionTicks = 0;
        status = actionMessage;
        return true;
    }

    private static double horizontalDistance(double x, double z, BlockPos target) {
        double dx = target.getX() + 0.5D - x;
        double dz = target.getZ() + 0.5D - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void clearPendingAction() {
        pendingActionPos = null;
        pendingActionBlock = null;
        pendingActionWalkTarget = null;
        pendingActionTicks = 0;
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
