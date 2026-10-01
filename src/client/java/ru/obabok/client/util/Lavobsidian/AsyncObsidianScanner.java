package ru.obabok.client.util.Lavobsidian;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockBox;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import ru.obabok.client.Scan;
import ru.obabok.common.model.BlockArea;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

public class AsyncObsidianScanner {
    private static final AtomicBoolean running = new AtomicBoolean();
    private static final Set<BlockPos> lavaMarkers = new HashSet<>();
    private static final Set<ChunkPos> unloadedChunks = ConcurrentHashMap.newKeySet();
    private static volatile PendingObsidianScan pendingScan;
    private static Level markerWorld;

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
                    Minecraft.getInstance().player.sendSystemMessage(Component.literal("[Lavabsidian] A scan is already running"));
                }
            });
            return;
        }

        try {
            pendingScan = new PendingObsidianScan(world, ranges);
            unloadedChunks.clear();
            unloadedChunks.addAll(pendingScan.getRequiredChunks());
            Scan.renderDirty = true;
            pendingScan.captureLoadedChunks();
            unloadedChunks.removeAll(pendingScan.getCapturedChunks());
            Scan.renderDirty = true;
            if (pendingScan.allChunksCaptured()) {
                beginScan(pendingScan);
            } else {
                report("[Lavobsidian] Waiting for chunks in the scan area. Visit the area to load them.");
            }
        } catch (RuntimeException exception) {
            pendingScan = null;
            unloadedChunks.clear();
            Scan.renderDirty = true;
            running.set(false);
            report("[Lavobsidian] Could not prepare scan: " + exception.getMessage());
        }
    }

    public static void onChunkLoaded(Level world, ChunkPos chunkPos) {
        PendingObsidianScan request = pendingScan;
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
        if (request.allChunksCaptured()) beginScan(request);
    }

    private static void beginScan(PendingObsidianScan request) {
        if (pendingScan != request) return;
        pendingScan = null;
        unloadedChunks.clear();
        Scan.renderDirty = true;
        report("[Lavobsidian] All scan chunks captured. Starting prediction.");

        Set<BlockPos> results = new HashSet<>();
        int totalLayers = request.regions.stream().mapToInt(region -> region.height).sum();
        AtomicInteger processedLayers = new AtomicInteger();
        AtomicInteger lastReportedPercent = new AtomicInteger(-1);
        Thread scanThread = new Thread(() -> {
            try {
                for (PendingObsidianScan.RegionSnapshot region : request.regions) {
                    int layerOffset = processedLayers.get();
                    int maxY = region.minY + region.height - 1;
                    ObsidianScanner.scan(region.blocks, region.minX, region.minY, region.minZ,
                            region.width, region.height, region.depth, pos -> results.add(pos.immutable()), currentY -> {
                                int layersProcessed = layerOffset + maxY - currentY + 1;
                                int percent = (int) (layersProcessed * 100.0 / totalLayers);
                                int roundedPercent = Math.min(100, (percent / 10) * 10);
                                int previousPercent = lastReportedPercent.get();
                                if (roundedPercent > previousPercent
                                        && lastReportedPercent.compareAndSet(previousPercent, roundedPercent)) {
                                    report("[Lavobsidian] Progress: " + roundedPercent + "%");
                                }
                            });
                    processedLayers.addAndGet(region.height);
                }
            } catch (RuntimeException exception) {
                Minecraft.getInstance().execute(() -> {
                    running.set(false);
                    unloadedChunks.clear();
                    Scan.renderDirty = true;
                    report("[Lavobsidian] Scan failed: " + exception.getMessage());
                });
                return;
            }

            Minecraft.getInstance().execute(() -> {
                try {
                    if (Minecraft.getInstance().level == request.world) {
                        for (BlockPos marker : results) {
                            if (Scan.selectedBlocks.add(marker)) lavaMarkers.add(marker);
                        }
                        markerWorld = request.world;
                        Scan.renderDirty = true;
                        report("[Lavobsidian] Scan complete. Found markers: " + results.size());
                    } else {
                        report("[Lavobsidian] Scan results discarded because the world changed");
                    }
                } finally {
                    unloadedChunks.clear();
                    Scan.renderDirty = true;
                    running.set(false);
                }
            });
        }, "Lavobsidian-Predictor");

        scanThread.setDaemon(true);
        scanThread.start();
    }

    public static void removeMarkersNear(Level world, BlockPos playerPos) {
        if (world == null || playerPos == null || lavaMarkers.isEmpty()) return;
        if (markerWorld != world) {
            markerWorld = world;
            lavaMarkers.clear();
            return;
        }

        int removed = 0;
        Iterator<BlockPos> iterator = lavaMarkers.iterator();
        while (iterator.hasNext()) {
            BlockPos marker = iterator.next();
            long dx = (long) marker.getX() - playerPos.getX();
            long dy = (long) marker.getY() - playerPos.getY();
            long dz = (long) marker.getZ() - playerPos.getZ();
            if (dx * dx + dy * dy + dz * dz <= 9) {
                Scan.selectedBlocks.remove(marker);
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            Scan.renderDirty = true;
            reportActionBar("[Lavobsidian] Cleared " + removed + " nearby markers");
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
}
