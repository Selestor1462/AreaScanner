package ru.obabok.client.util.Lavobsidian;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import ru.obabok.client.Scan;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncObsidianScanner {
    private static final AtomicBoolean running = new AtomicBoolean();
    private static final Set<BlockPos> lavaMarkers = new HashSet<>();
    private static final Set<ChunkPos> unloadedChunks = ConcurrentHashMap.newKeySet();
    private static volatile PendingObsidianScan pendingScan;
    private static Level markerWorld;

    public static void startAsyncScan(Level world, AABB range) {
        if (world == null || range == null) return;
        if (!running.compareAndSet(false, true)) {
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                            Component.literal("[Lavobsidian] A scan is already running"), false);
                }
            });
            return;
        }

        try {
            pendingScan = new PendingObsidianScan(world, range);
            unloadedChunks.clear();
            unloadedChunks.addAll(pendingScan.getRequiredChunks());
            pendingScan.captureLoadedChunks();
            unloadedChunks.removeAll(pendingScan.getCapturedChunks());
            if (pendingScan.allChunksCaptured()) {
                beginScan(pendingScan);
            } else {
                report("[Lavobsidian] Waiting for chunks in the scan area. Visit the area to load them.");
            }
        } catch (RuntimeException exception) {
            pendingScan = null;
            unloadedChunks.clear();
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
            running.set(false);
            report("[Lavobsidian] Pending scan cancelled because the world changed");
            return;
        }
        if (world != request.world || !request.captureChunk(chunkPos)) return;
        if (request.isChunkCaptured(chunkPos)) unloadedChunks.remove(chunkPos);
        if (request.allChunksCaptured()) beginScan(request);
    }

    private static void beginScan(PendingObsidianScan request) {
        if (pendingScan != request) return;
        pendingScan = null;
        unloadedChunks.clear();
        report("[Lavobsidian] All scan chunks captured. Starting prediction.");

        Set<BlockPos> results = new HashSet<>();
        PendingObsidianScan.RegionSnapshot region = request.region;
        AtomicInteger lastReportedPercent = new AtomicInteger(-1);
        Thread scanThread = new Thread(() -> {
            try {
                ObsidianScanner.scan(region.blocks, region.minX, region.minY, region.minZ,
                        region.width, region.height, region.depth, pos -> results.add(pos.immutable()), currentY -> {
                            int layersProcessed = region.maxY - currentY + 1;
                            int percent = (int) (layersProcessed * 100.0 / region.height);
                            int roundedPercent = Math.min(100, (percent / 10) * 10);
                            int previousPercent = lastReportedPercent.get();
                            if (roundedPercent > previousPercent
                                    && lastReportedPercent.compareAndSet(previousPercent, roundedPercent)) {
                                report("[Lavobsidian] Progress: " + roundedPercent + "%");
                            }
                        });
            } catch (RuntimeException exception) {
                Minecraft.getInstance().execute(() -> {
                    running.set(false);
                    unloadedChunks.clear();
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
                        report("[Lavobsidian] Scan complete. Found markers: " + results.size());
                    } else {
                        report("[Lavobsidian] Scan results discarded because the world changed");
                    }
                } finally {
                    unloadedChunks.clear();
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
        if (removed > 0) reportActionBar("[Lavobsidian] Cleared " + removed + " nearby markers");
    }

    private static void report(String message) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(Component.literal(message), false);
            }
        });
    }

    private static void reportActionBar(String message) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(Component.literal(message), true);
            }
        });
    }

    public static Set<ChunkPos> getUnloadedChunks() {
        return new HashSet<>(unloadedChunks);
    }
}