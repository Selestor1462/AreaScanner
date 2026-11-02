package ru.obabok.client.util.Lavobsidian;

import com.mojang.blaze3d.vertex.PoseStack;
import fi.dy.masa.malilib.util.data.Color4f;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ru.obabok.client.Scan;
import ru.obabok.client.render.RenderUtil;
import ru.obabok.common.model.BlockArea;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AsyncObsidianScanner {
    private static final AtomicBoolean running = new AtomicBoolean();
    private static final AtomicLong generation = new AtomicLong();

    private static final List<LavaMarker> waypoints = new CopyOnWriteArrayList<>();
    private static final Set<BlockPos> predictedObsidianBlocks = ConcurrentHashMap.newKeySet();
    private static final Set<BlockPos> rememberedWaterFlows = ConcurrentHashMap.newKeySet();
    private static final Set<ChunkPos> unloadedChunks = ConcurrentHashMap.newKeySet();

    private static volatile PendingObsidianScan pendingScan;
    private static Level markerWorld;
    private static int tickCounter = 0;

    public static void startAsyncScan(Level world, AABB range) {
        if (range == null) return;
        startAsyncScan(world, List.of(range));
    }

    public static void startAsyncScan(Level world, BlockArea area) {
        if (area == null || area.isEmpty()) return;
        List<AABB> ranges = new ArrayList<>(area.size());
        for (BlockBox box : area.getBoxes()) {
            ranges.add(new AABB(
                    box.min().getX(), box.min().getY(), box.min().getZ(),
                    box.max().getX(), box.max().getY(), box.max().getZ()
            ));
        }
        startAsyncScan(world, ranges);
    }

    private static void startAsyncScan(Level world, List<AABB> ranges) {
        if (world == null || ranges.isEmpty()) return;
        if (!running.compareAndSet(false, true)) {
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.sendSystemMessage(Component.literal("[Lavobsidian] A scan is already running"));
                }
            });
            return;
        }
        long runId = generation.incrementAndGet();

        try {
            pendingScan = new PendingObsidianScan(world, ranges);
            unloadedChunks.clear();
            unloadedChunks.addAll(pendingScan.getRequiredChunks());
            Scan.renderDirty = true;
            pendingScan.captureLoadedChunks();
            unloadedChunks.removeAll(pendingScan.getCapturedChunks());
            Scan.renderDirty = true;
            if (pendingScan.allChunksCaptured()) {
                beginScan(pendingScan, runId);
            } else {
                report("[Lavobsidian] Waiting for chunks in the scan area. Visit the area to load them.");
            }
        } catch (RuntimeException exception) {
            if (generation.get() != runId) return;
            pendingScan = null;
            unloadedChunks.clear();
            Scan.renderDirty = true;
            running.set(false);
            report("[Lavobsidian] Could not prepare scan: " + exception.getMessage());
        }
    }

    public static void onChunkLoaded(Level world, ChunkPos chunkPos) {
        PendingObsidianScan request = pendingScan;
        long runId = generation.get();
        if (request == null || chunkPos == null) return;
        if (Minecraft.getInstance().level != request.world) {
            pendingScan = null;
            unloadedChunks.clear();
            Scan.renderDirty = true;
            running.set(false);
            report("[Lavobsidian] Pending scan cancelled because the world changed");
            return;
        }
        if (world != request.world || !request.captureChunk(chunkPos)) return;
        if (request.isChunkCaptured(chunkPos)) {
            unloadedChunks.remove(chunkPos);
            Scan.renderDirty = true;
        }
        if (request.allChunksCaptured()) beginScan(request, runId);
    }

    private static void beginScan(PendingObsidianScan request, long runId) {
        if (pendingScan != request || generation.get() != runId) return;
        pendingScan = null;
        unloadedChunks.clear();
        Scan.renderDirty = true;
        report("[Lavobsidian] All scan chunks captured. Starting prediction.");

        int totalLayers = request.regions.stream().mapToInt(region -> region.height).sum();
        AtomicInteger processedLayers = new AtomicInteger();
        AtomicInteger lastReportedPercent = new AtomicInteger(-1);

        Thread scanThread = new Thread(() -> {
            List<LavaMarker> scannedWaypoints = new ArrayList<>();
            Set<BlockPos> scannedObsidian = new HashSet<>();
            Set<BlockPos> scannedFlows = new HashSet<>();

            try {
                for (PendingObsidianScan.RegionSnapshot region : request.regions) {
                    int layerOffset = processedLayers.get();
                    int maxY = region.minY + region.height - 1;

                    ObsidianScanner.ScanOutput output = ObsidianScanner.scan(
                            region.blocks, region.minX, region.minY, region.minZ,
                            region.width, region.height, region.depth, region.specialBlocks,
                            currentY -> {
                                int layersProcessed = layerOffset + maxY - currentY + 1;
                                int percent = (int) (layersProcessed * 100.0 / totalLayers);
                                int roundedPercent = Math.min(100, (percent / 10) * 10);
                                int previousPercent = lastReportedPercent.get();
                                if (roundedPercent > previousPercent
                                        && lastReportedPercent.compareAndSet(previousPercent, roundedPercent)) {
                                    report("[Lavobsidian] Progress: " + roundedPercent + "%");
                                }
                            }
                    );

                    scannedWaypoints.addAll(output.waypoints);
                    scannedObsidian.addAll(output.predictedObsidian);
                    scannedFlows.addAll(output.waterFlowPoints);
                    processedLayers.addAndGet(region.height);
                }
            } catch (RuntimeException exception) {
                Minecraft.getInstance().execute(() -> {
                    if (generation.get() != runId) return;
                    running.set(false);
                    unloadedChunks.clear();
                    Scan.renderDirty = true;
                    report("[Lavobsidian] Scan failed: " + exception.getMessage());
                });
                return;
            }

            Minecraft.getInstance().execute(() -> {
                try {
                    if (generation.get() != runId) return;
                    if (Minecraft.getInstance().level == request.world) {
                        waypoints.clear();
                        predictedObsidianBlocks.clear();
                        rememberedWaterFlows.clear();

                        waypoints.addAll(scannedWaypoints);
                        predictedObsidianBlocks.addAll(scannedObsidian);
                        rememberedWaterFlows.addAll(scannedFlows);
                        markerWorld = request.world;
                        Scan.renderDirty = true;
                        report("[Lavobsidian] Scan complete. Found " + waypoints.size() + " waypoints (" + predictedObsidianBlocks.size() + " predicted obsidian blocks)");
                    } else {
                        report("[Lavobsidian] Scan results discarded because the world changed");
                    }
                } finally {
                    if (generation.get() == runId) {
                        unloadedChunks.clear();
                        Scan.renderDirty = true;
                        running.set(false);
                    }
                }
            });
        }, "Lavobsidian-Predictor");

        scanThread.setDaemon(true);
        scanThread.start();
    }

    public static void tick(Level world) {
        if (world == null || (waypoints.isEmpty() && predictedObsidianBlocks.isEmpty())) return;
        if (markerWorld != world) {
            stopAndClear();
            markerWorld = world;
            return;
        }

        tickCounter++;
        if (tickCounter % 4 != 0) return;

        List<LavaMarker> toRemove = new ArrayList<>();
        for (LavaMarker marker : waypoints) {
            if (marker.type == LavaMarker.Type.LAVA_WAYPOINT) {
                if (isLavaCleared(world, marker.trackedLavaBlocks)) {
                    toRemove.add(marker);
                }
            } else {
                if (isBlastResistantCleared(world, marker.pos)) {
                    toRemove.add(marker);
                }
            }
        }

        if (!toRemove.isEmpty()) {
            for (LavaMarker marker : toRemove) {
                waypoints.remove(marker);
                predictedObsidianBlocks.removeAll(marker.predictedObsidianBlocks);
                if (marker.type == LavaMarker.Type.LAVA_WAYPOINT) {
                    recheckLocalArea(world, marker.pos);
                } else {
                    reportActionBar("[Lavobsidian] Cleared marker at " + marker.pos.toShortString());
                }
            }
            Scan.renderDirty = true;
        }
    }

    private static boolean isLavaCleared(Level world, Set<BlockPos> trackedLava) {
        if (trackedLava.isEmpty()) return true;
        for (BlockPos pos : trackedLava) {
            if (!world.hasChunkAt(pos)) {
                return false;
            }
            FluidState fluid = world.getFluidState(pos);
            if (fluid.is(Fluids.LAVA)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlastResistantCleared(Level world, BlockPos pos) {
        if (!world.hasChunkAt(pos)) return false;
        BlockState state = world.getBlockState(pos);
        return !ObsidianScanner.isBlastResistantState(state);
    }

    private static void recheckLocalArea(Level world, BlockPos center) {
        int radiusXZ = 14;
        int radiusY = 10;
        int minX = center.getX() - radiusXZ;
        int maxX = center.getX() + radiusXZ;
        int minY = Math.max(world.getMinY(), center.getY() - radiusY);
        int maxY = Math.min(world.getMaxY() - 1, center.getY() + radiusY);
        int minZ = center.getZ() - radiusXZ;
        int maxZ = center.getZ() + radiusXZ;

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!world.hasChunk(cx, cz)) {
                    reportActionBar("[Lavobsidian] Cleared waypoint (all lava resolved)");
                    return;
                }
            }
        }

        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        int depth = maxZ - minZ + 1;
        byte[] blockData = new byte[width * height * depth];
        Map<BlockPos, LavaMarker.Type> specialBlocks = new HashMap<>();
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mpos.set(x, y, z);
                    BlockState state = world.getBlockState(mpos);
                    int idx = ((y - minY) * width + (x - minX)) * depth + (z - minZ);
                    blockData[idx] = ObsidianScanner.classify(state);
                    if (ObsidianScanner.isBlastResistantState(state)) {
                        specialBlocks.put(mpos.immutable(), ObsidianScanner.getBlastResistantType(state));
                    }
                }
            }
        }

        long runId = generation.get();
        Set<BlockPos> flowsSnapshot = new HashSet<>(rememberedWaterFlows);

        CompletableFuture.supplyAsync(() -> {
            return ObsidianScanner.scanLocal(blockData, minX, minY, minZ, width, height, depth,
                    flowsSnapshot, specialBlocks);
        }).thenAccept(output -> {
            Minecraft.getInstance().execute(() -> {
                if (generation.get() != runId || Minecraft.getInstance().level != world) return;

                int newFormationsCount = 0;
                for (LavaMarker newMarker : output.waypoints) {
                    if (newMarker.type == LavaMarker.Type.LAVA_WAYPOINT) {
                        boolean alreadyCovered = false;
                        for (LavaMarker existing : waypoints) {
                            if (existing.pos.equals(newMarker.pos) || !Collections.disjoint(existing.trackedLavaBlocks, newMarker.trackedLavaBlocks)) {
                                alreadyCovered = true;
                                break;
                            }
                        }
                        if (!alreadyCovered && !isLavaCleared(world, newMarker.trackedLavaBlocks)) {
                            waypoints.add(newMarker);
                            predictedObsidianBlocks.addAll(newMarker.predictedObsidianBlocks);
                            newFormationsCount++;
                        }
                    }
                }
                rememberedWaterFlows.addAll(output.waterFlowPoints);

                if (newFormationsCount > 0) {
                    Scan.renderDirty = true;
                    reportActionBar("[Lavobsidian] Cleared waypoint. Found " + newFormationsCount + " new obsidian formation(s)!");
                } else {
                    reportActionBar("[Lavobsidian] Cleared waypoint (all lava resolved)");
                }
            });
        });
    }

    public static boolean hasRenderableMarkers() {
        return !waypoints.isEmpty() || !predictedObsidianBlocks.isEmpty();
    }

    public static void render(LevelRenderContext context) {
        if (!hasRenderableMarkers()) return;
        PoseStack matrices = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;

        // 1. Render predicted obsidian blocks (fiery orange)
        Color4f obsidianColor = LavaMarker.Type.PREDICTED_OBSIDIAN.defaultColor;
        for (BlockPos pos : predictedObsidianBlocks) {
            RenderUtil.newRenderFilledBox(matrices.last().pose(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
                    camera, obsidianColor);
        }

        // 2. Render waypoints (lava pool centers & blast resistant blocks)
        for (LavaMarker marker : waypoints) {
            BlockPos pos = marker.pos;
            RenderUtil.newRenderFilledBox(matrices.last().pose(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
                    camera, marker.color);
        }
    }

    private static void report(String message) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.sendSystemMessage(Component.literal(message));
            }
        });
    }

    private static void reportActionBar(String message) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.sendOverlayMessage(Component.literal(message));
            }
        });
    }

    public static Set<ChunkPos> getUnloadedChunks() {
        return new HashSet<>(unloadedChunks);
    }

    public static int getMarkerCount() {
        return waypoints.size();
    }

    public static BlockPos getNearestMarker(BlockPos playerPos) {
        if (waypoints.isEmpty()) return null;
        if (playerPos == null) return waypoints.get(0).pos;

        LavaMarker nearest = null;
        long minDistSq = Long.MAX_VALUE;
        for (LavaMarker marker : waypoints) {
            long dx = (long) marker.pos.getX() - playerPos.getX();
            long dy = (long) marker.pos.getY() - playerPos.getY();
            long dz = (long) marker.pos.getZ() - playerPos.getZ();
            long distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < minDistSq) {
                minDistSq = distSq;
                nearest = marker;
            }
        }
        return nearest != null ? nearest.pos : null;
    }

    public static boolean canStopOrClear() {
        return running.get() || !waypoints.isEmpty() || !predictedObsidianBlocks.isEmpty() || !unloadedChunks.isEmpty();
    }

    public static void stopAndClear() {
        generation.incrementAndGet();
        pendingScan = null;
        unloadedChunks.clear();
        running.set(false);
        int removed = waypoints.size() + predictedObsidianBlocks.size();
        waypoints.clear();
        predictedObsidianBlocks.clear();
        rememberedWaterFlows.clear();
        markerWorld = null;
        Scan.renderDirty = true;
        if (removed > 0) reportActionBar("[Lavobsidian] Cleared all lava markers");
    }
}
