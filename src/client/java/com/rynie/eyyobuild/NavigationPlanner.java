package com.rynie.eyyobuild;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Bounded A* ground navigator for EYYOBUILD.
 *
 * It plans over walkable surfaces, one-block jumps, safe drops of up to three
 * blocks, and breakable two-block-tall obstacles. Server actions are still
 * performed by BuildEngine through ordinary client interactions.
 */
public final class NavigationPlanner {
    private static final int MAX_DROP = 3;
    private static final double DIAGONAL_COST = 1.41421356237D;
    private static final double UNREACHABLE = Double.POSITIVE_INFINITY;

    private NavigationPlanner() {}

    public static List<BlockPos> findPath(
            Minecraft client,
            BlockPos start,
            BlockPos target,
            Predicate<BlockPos> goal,
            int maxNodes,
            int horizontalRadius,
            int verticalRange
    ) {
        if (client == null || client.level == null || start == null || target == null || goal == null) {
            return List.of();
        }
        if (goal.test(start)) return List.of(start);

        PriorityQueue<QueueNode> open = new PriorityQueue<>(
                Comparator.comparingDouble(QueueNode::estimatedTotal)
                        .thenComparingDouble(QueueNode::pathCost)
        );
        Map<BlockPos, Double> costs = new HashMap<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        costs.put(start, 0.0D);
        open.add(new QueueNode(start, 0.0D, heuristic(start, target)));
        BlockPos reached = null;
        int examined = 0;

        while (!open.isEmpty() && examined < maxNodes) {
            QueueNode currentNode = open.poll();
            BlockPos current = currentNode.position();
            double knownCost = costs.getOrDefault(current, UNREACHABLE);
            if (currentNode.pathCost() > knownCost + 0.0001D || !closed.add(current)) {
                continue;
            }

            examined++;
            if (goal.test(current)) {
                reached = current;
                break;
            }

            if (Math.abs(current.getX() - start.getX()) > horizontalRadius
                    || Math.abs(current.getZ() - start.getZ()) > horizontalRadius
                    || Math.abs(current.getY() - start.getY()) > verticalRange) {
                continue;
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    boolean diagonal = dx != 0 && dz != 0;

                    if (diagonal && !diagonalCorridorClear(client, current, dx, dz)) {
                        continue;
                    }

                    for (int dy = 1; dy >= -MAX_DROP; dy--) {
                        BlockPos next = current.offset(dx, dy, dz);
                        if (closed.contains(next) || !isStandableOrMineable(client, next)) {
                            continue;
                        }
                        if (dy < 0 && !safeDropColumn(client, current, next)) {
                            continue;
                        }

                        double movementCost = diagonal ? DIAGONAL_COST : 1.0D;
                        if (dy > 0) movementCost += 1.7D;
                        if (dy < 0) movementCost += 0.55D * Math.abs(dy);
                        movementCost += obstacleCost(client, next);
                        movementCost += obstacleCost(client, next.above());

                        double candidateCost = knownCost + movementCost;
                        if (candidateCost + 0.0001D >= costs.getOrDefault(next, UNREACHABLE)) {
                            continue;
                        }

                        costs.put(next, candidateCost);
                        previous.put(next, current);
                        open.add(new QueueNode(next, candidateCost,
                                candidateCost + heuristic(next, target)));
                    }
                }
            }
        }

        if (reached == null) return List.of();

        List<BlockPos> result = new ArrayList<>();
        for (BlockPos cursor = reached; cursor != null; cursor = previous.get(cursor)) {
            result.add(cursor);
            if (cursor.equals(start)) break;
        }
        Collections.reverse(result);
        return result.isEmpty() || !result.get(0).equals(start) ? List.of() : result;
    }

    /** Strict standing check used for the player's current position and bridge logic. */
    public static boolean isWalkable(Minecraft client, BlockPos feet) {
        if (client == null || client.level == null || feet == null) return false;

        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        BlockState feetState = client.level.getBlockState(feet);
        BlockState headState = client.level.getBlockState(head);
        BlockState floorState = client.level.getBlockState(floor);

        return feetState.getCollisionShape(client.level, feet).isEmpty()
                && headState.getCollisionShape(client.level, head).isEmpty()
                && feetState.getFluidState().isEmpty()
                && headState.getFluidState().isEmpty()
                && !isHazard(feetState, floor)
                && !floorState.getCollisionShape(client.level, floor).isEmpty()
                && floorState.getFluidState().isEmpty();
    }

    private static boolean isStandableOrMineable(Minecraft client, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        BlockState feetState = client.level.getBlockState(feet);
        BlockState headState = client.level.getBlockState(head);
        BlockState floorState = client.level.getBlockState(floor);

        if (!feetState.getFluidState().isEmpty() || !headState.getFluidState().isEmpty()
                || floorState.getFluidState().isPresent()) {
            return false;
        }
        if (isHazard(floorState, floor)) return false;
        if (floorState.getCollisionShape(client.level, floor).isEmpty()) return false;

        return canClearCell(client, feet, feetState) && canClearCell(client, head, headState);
    }

    private static boolean canClearCell(Minecraft client, BlockPos pos, BlockState state) {
        if (state.getCollisionShape(client.level, pos).isEmpty()) return true;
        if (client.level.getBlockEntity(pos) != null || isHazard(state, pos)) return false;
        float hardness = state.getDestroySpeed(client.level, pos);
        return hardness >= 0.0F && Float.isFinite(hardness);
    }

    private static double obstacleCost(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        VoxelShape collision = state.getCollisionShape(client.level, pos);
        if (collision.isEmpty()) return 0.0D;
        if (!canClearCell(client, pos, state)) return UNREACHABLE;
        double hardness = Math.max(0.0D, state.getDestroySpeed(client.level, pos));
        return 7.0D + hardness * 2.0D;
    }

    private static boolean diagonalCorridorClear(Minecraft client, BlockPos current, int dx, int dz) {
        BlockPos xSide = current.offset(dx, 0, 0);
        BlockPos zSide = current.offset(0, 0, dz);
        return clearColumn(client, xSide) && clearColumn(client, zSide);
    }

    private static boolean clearColumn(Minecraft client, BlockPos feet) {
        BlockPos head = feet.above();
        BlockState feetState = client.level.getBlockState(feet);
        BlockState headState = client.level.getBlockState(head);
        return feetState.getFluidState().isEmpty()
                && headState.getFluidState().isEmpty()
                && feetState.getCollisionShape(client.level, feet).isEmpty()
                && headState.getCollisionShape(client.level, head).isEmpty();
    }

    /** A falling player must have a clear, non-liquid column all the way to the landing tile. */
    private static boolean safeDropColumn(Minecraft client, BlockPos from, BlockPos to) {
        if (from.getY() - to.getY() > MAX_DROP) return false;
        for (int y = to.getY() + 1; y <= from.getY() + 1; y++) {
            BlockPos feetCell = new BlockPos(to.getX(), y, to.getZ());
            BlockPos headCell = feetCell.above();
            BlockState feetState = client.level.getBlockState(feetCell);
            BlockState headState = client.level.getBlockState(headCell);
            if (!feetState.getCollisionShape(client.level, feetCell).isEmpty()
                    || !headState.getCollisionShape(client.level, headCell).isEmpty()
                    || !feetState.getFluidState().isEmpty()
                    || !headState.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHazard(BlockState state, BlockPos pos) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return id.contains("lava")
                || id.equals("fire")
                || id.equals("soul_fire")
                || id.equals("cactus")
                || id.equals("magma_block")
                || id.equals("sweet_berry_bush")
                || id.equals("powder_snow")
                || id.equals("campfire")
                || id.equals("soul_campfire");
    }

    private static double heuristic(BlockPos from, BlockPos target) {
        double horizontal = Math.max(0.0D,
                Math.abs(from.getX() - target.getX())
                        + Math.abs(from.getZ() - target.getZ()) - 5.0D);
        double vertical = Math.max(0.0D, Math.abs(from.getY() - target.getY()) - 2.0D) * 1.25D;
        return horizontal + vertical;
    }

    private record QueueNode(BlockPos position, double pathCost, double estimatedTotal) {}
}
