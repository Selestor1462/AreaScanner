package ru.obabok.client.util;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import ru.obabok.client.Scan;
import ru.obabok.common.model.BlockArea;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class DebrisScanner {
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile int threshold = 5;
    private static volatile BlockArea area;
    private static volatile Level level;

    private static final Long2ObjectOpenHashMap<HashSet<BlockPos>> xRows = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<HashSet<BlockPos>> zRows = new Long2ObjectOpenHashMap<>();
    private static final Map<ChunkPos, List<BlockPos>> chunkDebrisMap = new ConcurrentHashMap<>();

    private static final Set<ChunkPos> pendingChunks = ConcurrentHashMap.newKeySet();
    private static final Set<ChunkPos> scannedChunks = ConcurrentHashMap.newKeySet();
    private static final Set<BlockPos> qualifyingDebris = ConcurrentHashMap.newKeySet();

    private static volatile long allChunksCount = 0;
    private static ExecutorService scanExecutor;

    public static long getXRowKey(int y, int z) {
        return (((long) y) << 32) | (z & 0xFFFFFFFFL);
    }

    public static long getZRowKey(int y, int x) {
        return (((long) y) << 32) | (x & 0xFFFFFFFFL);
    }

    public static boolean isScanning() {
        return running.get();
    }

    public static int getThreshold() {
        return threshold;
    }

    public static long getAllChunksCount() {
        return allChunksCount;
    }

    public static int getPendingChunksCount() {
        return pendingChunks.size();
    }

    public static int getQualifyingDebrisCount() {
        return qualifyingDebris.size();
    }

    public static synchronized int getQualifyingRowCount() {
        int count = 0;
        int t = threshold;
        for (HashSet<BlockPos> set : xRows.values()) {
            if (set.size() > t) count++;
        }
        for (HashSet<BlockPos> set : zRows.values()) {
            if (set.size() > t) count++;
        }
        return count;
    }

    public static void startScan(Level world, BlockArea scanArea, int targetThreshold) {
        stopScan();

        if (world == null || scanArea == null || scanArea.isEmpty()) {
            return;
        }

        running.set(true);
        threshold = Math.max(1, targetThreshold);
        area = scanArea;
        level = world;

        if (scanExecutor == null || scanExecutor.isShutdown()) {
            scanExecutor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
        }

        Set<ChunkPos> affected = area.getAffectedChunks();
        allChunksCount = affected.size();
        pendingChunks.clear();
        pendingChunks.addAll(affected);
        scannedChunks.clear();

        synchronized (Scan.selectedBlocks) {
            Scan.selectedBlocks.clear();
            Scan.unloadedChunks.clear();
            Scan.unloadedChunks.addAll(affected);
            Scan.renderDirty = true;
        }

        CompletableFuture.runAsync(() -> {
            // First pass: scan currently loaded chunks in ClientLevel
            for (ChunkPos cp : affected) {
                if (!running.get()) return;
                ChunkAccess chunk = world.getChunkSource().getChunk(cp.x(), cp.z(), ChunkStatus.FULL, false);
                if (chunk != null) {
                    List<BlockPos> debris = scanChunk(chunk, area);
                    processChunkDebris(cp, debris);
                }
            }

            // Second pass: if in singleplayer, scan remaining unloaded chunks from IntegratedServer save
            if (running.get() && Minecraft.getInstance().hasSingleplayerServer()) {
                scanSingleplayerUnloadedChunks(affected);
            }

            reportScanStatus();
        }, scanExecutor);
    }

    private static void scanSingleplayerUnloadedChunks(Set<ChunkPos> affected) {
        try {
            IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server == null || level == null) return;
            ServerLevel serverLevel = server.getLevel(level.dimension());
            if (serverLevel == null) return;

            for (ChunkPos cp : affected) {
                if (!running.get()) return;
                if (scannedChunks.contains(cp)) continue;

                try {
                    ChunkAccess chunk = serverLevel.getChunk(cp.x(), cp.z(), ChunkStatus.EMPTY, false);
                    if (chunk != null) {
                        List<BlockPos> debris = scanChunk(chunk, area);
                        processChunkDebris(cp, debris);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    public static void stopScan() {
        running.set(false);
        synchronized (DebrisScanner.class) {
            xRows.clear();
            zRows.clear();
            chunkDebrisMap.clear();
            pendingChunks.clear();
            scannedChunks.clear();
            qualifyingDebris.clear();
            allChunksCount = 0;
            area = null;
            level = null;
        }
        if (scanExecutor != null && !scanExecutor.isShutdown()) {
            scanExecutor.shutdownNow();
            scanExecutor = null;
        }
    }

    public static List<BlockPos> scanChunk(ChunkAccess chunk, BlockArea scanArea) {
        List<BlockPos> found = new ArrayList<>();
        if (chunk == null || scanArea == null) return found;

        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null) return found;

        ChunkPos chunkPos = chunk.getPos();
        List<BlockBox> relevantBoxes = scanArea.getBoxesIntersectingChunk(chunkPos);
        if (relevantBoxes.isEmpty()) return found;

        int chunkMinX = chunkPos.getMinBlockX();
        int chunkMinZ = chunkPos.getMinBlockZ();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) continue;
            // Palette check: instantly rejects sections without ancient debris
            if (!section.maybeHas(state -> state.is(Blocks.ANCIENT_DEBRIS))) continue;

            int bottomY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
            int topY = bottomY + 15;

            boolean intersectsY = false;
            for (BlockBox box : relevantBoxes) {
                if (box.min().getY() <= topY && box.max().getY() >= bottomY) {
                    intersectsY = true;
                    break;
                }
            }
            if (!intersectsY) continue;

            for (int localY = 0; localY < 16; localY++) {
                int y = bottomY + localY;
                for (int localZ = 0; localZ < 16; localZ++) {
                    int z = chunkMinZ + localZ;
                    for (int localX = 0; localX < 16; localX++) {
                        int x = chunkMinX + localX;
                        BlockState state = section.getBlockState(localX, localY, localZ);
                        if (state.is(Blocks.ANCIENT_DEBRIS)) {
                            if (scanArea.contains(x, y, z)) {
                                found.add(new BlockPos(x, y, z));
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    public static synchronized void processChunkDebris(ChunkPos chunkPos, List<BlockPos> debrisList) {
        if (!running.get()) return;
        if (!scannedChunks.add(chunkPos)) return;
        pendingChunks.remove(chunkPos);

        synchronized (Scan.selectedBlocks) {
            Scan.unloadedChunks.remove(chunkPos);
        }

        if (debrisList != null && !debrisList.isEmpty()) {
            chunkDebrisMap.put(chunkPos, debrisList);
            for (BlockPos pos : debrisList) {
                long xKey = getXRowKey(pos.getY(), pos.getZ());
                xRows.computeIfAbsent(xKey, k -> new HashSet<>()).add(pos);

                long zKey = getZRowKey(pos.getY(), pos.getX());
                zRows.computeIfAbsent(zKey, k -> new HashSet<>()).add(pos);
            }
        }

        recalculateQualifyingDebris();
    }

    public static synchronized void recalculateQualifyingDebris() {
        int t = threshold;
        Set<BlockPos> newQualifying = new HashSet<>();

        for (HashSet<BlockPos> set : xRows.values()) {
            if (set.size() > t) {
                newQualifying.addAll(set);
            }
        }

        for (HashSet<BlockPos> set : zRows.values()) {
            if (set.size() > t) {
                newQualifying.addAll(set);
            }
        }

        qualifyingDebris.clear();
        qualifyingDebris.addAll(newQualifying);

        synchronized (Scan.selectedBlocks) {
            Scan.selectedBlocks.clear();
            Scan.selectedBlocks.addAll(newQualifying);
            Scan.renderDirty = true;
        }
    }

    public static void onChunkLoaded(Level world, ChunkPos chunkPos) {
        if (!running.get() || area == null || world != level || !area.intersectsChunk(chunkPos)) return;
        if (scannedChunks.contains(chunkPos)) return;

        ChunkAccess chunk = world.getChunkSource().getChunk(chunkPos.x(), chunkPos.z(), ChunkStatus.FULL, false);
        if (chunk != null) {
            if (scanExecutor != null && !scanExecutor.isShutdown()) {
                scanExecutor.execute(() -> {
                    List<BlockPos> debris = scanChunk(chunk, area);
                    processChunkDebris(chunkPos, debris);
                });
            } else {
                List<BlockPos> debris = scanChunk(chunk, area);
                processChunkDebris(chunkPos, debris);
            }
        }
    }

    public static synchronized void onBlockBreak(BlockPos pos) {
        if (!running.get()) return;
        if (!qualifyingDebris.contains(pos)) return;

        long xKey = getXRowKey(pos.getY(), pos.getZ());
        HashSet<BlockPos> xSet = xRows.get(xKey);
        if (xSet != null) xSet.remove(pos);

        long zKey = getZRowKey(pos.getY(), pos.getX());
        HashSet<BlockPos> zSet = zRows.get(zKey);
        if (zSet != null) zSet.remove(pos);

        recalculateQualifyingDebris();
    }

    private static void reportScanStatus() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.execute(() -> {
                if (mc.player == null) return;
                int rows = getQualifyingRowCount();
                int count = qualifyingDebris.size();
                long scanned = scannedChunks.size();
                mc.player.sendSystemMessage(Component.literal(
                        String.format("[AreaScanner] Debris row scan: checked %d/%d chunks. Found %d debris in %d rows (> %d)",
                                scanned, allChunksCount, count, rows, threshold)
                ).withColor(TextColor.fromRgb(0xFFA500)));

                if (count > 0 && scanned >= allChunksCount) {
                    mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1, 1);
                }
            });
        }
    }
}
