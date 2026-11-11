package ru.obabok.client.util;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import ru.obabok.client.Scan;
import ru.obabok.common.model.BlockArea;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class DebrisScanner {
    public enum AxisMode {
        BOTH("X & Z"),
        X_ONLY("X only"),
        Z_ONLY("Z only");

        private final String display;

        AxisMode(String display) {
            this.display = display;
        }

        public String getDisplay() {
            return display;
        }

        public AxisMode next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public static AxisMode fromString(String str) {
            if (str == null) return BOTH;
            String lower = str.trim().toLowerCase(Locale.ROOT);
            if (lower.equals("x") || lower.equals("x_only") || lower.equals("xonly")) return X_ONLY;
            if (lower.equals("z") || lower.equals("z_only") || lower.equals("zonly")) return Z_ONLY;
            return BOTH;
        }
    }

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile int threshold = 5;
    private static volatile AxisMode axisMode = AxisMode.BOTH;
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

    public static AxisMode getAxisMode() {
        return axisMode;
    }

    public static void setAxisMode(AxisMode mode) {
        axisMode = mode != null ? mode : AxisMode.BOTH;
        if (running.get() || !chunkDebrisMap.isEmpty()) {
            recalculateQualifyingDebris();
        }
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
        if (axisMode == AxisMode.BOTH || axisMode == AxisMode.X_ONLY) {
            for (HashSet<BlockPos> set : xRows.values()) {
                if (set.size() > t) count++;
            }
        }
        if (axisMode == AxisMode.BOTH || axisMode == AxisMode.Z_ONLY) {
            for (HashSet<BlockPos> set : zRows.values()) {
                if (set.size() > t) count++;
            }
        }
        return count;
    }

    public static void startScan(Level world, BlockArea scanArea, int targetThreshold) {
        startScan(world, scanArea, targetThreshold, axisMode);
    }

    public static void startScan(Level world, BlockArea scanArea, int targetThreshold, AxisMode mode) {
        stopScan();

        if (world == null || scanArea == null || scanArea.isEmpty()) {
            return;
        }

        running.set(true);
        threshold = Math.max(1, targetThreshold);
        axisMode = mode != null ? mode : AxisMode.BOTH;
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

            recalculateQualifyingDebris();
            reportScanStatus();
        }, scanExecutor);
    }

    private static void scanSingleplayerUnloadedChunks(Set<ChunkPos> affected) {
        try {
            IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server == null || level == null) return;
            ServerLevel serverLevel = server.getLevel(level.dimension());
            if (serverLevel == null) return;

            SimpleRegionStorage storage = serverLevel.getChunkSource().chunkMap;
            PalettedContainerFactory factory = PalettedContainerFactory.create(serverLevel.registryAccess());

            List<ChunkPos> remaining = new ArrayList<>();
            for (ChunkPos cp : affected) {
                if (!scannedChunks.contains(cp)) {
                    remaining.add(cp);
                }
            }

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (ChunkPos cp : remaining) {
                if (!running.get()) return;

                // 1. If currently loaded in server memory as a full chunk:
                ChunkAccess loaded = serverLevel.getChunkSource().getChunk(cp.x(), cp.z(), ChunkStatus.FULL, false);
                if (loaded != null) {
                    List<BlockPos> debris = scanChunk(loaded, area);
                    processChunkDebris(cp, debris);
                    continue;
                }

                // 2. Read from disk via SimpleRegionStorage:
                CompletableFuture<Void> future = storage.read(cp).thenAcceptAsync(nbtOpt -> {
                    if (!running.get()) return;
                    if (nbtOpt != null && nbtOpt.isPresent()) {
                        try {
                            CompoundTag tag = storage.upgradeChunkTag(nbtOpt.get(), -1);
                            SerializableChunkData data = SerializableChunkData.parse(serverLevel, factory, tag);
                            List<BlockPos> debris = scanSerializableChunkData(data, cp, area);
                            processChunkDebris(cp, debris);
                        } catch (Exception e) {
                            processChunkDebris(cp, Collections.emptyList());
                        }
                    } else {
                        // Chunk not generated / empty
                        processChunkDebris(cp, Collections.emptyList());
                    }
                }, scanExecutor);
                futures.add(future);

                if (futures.size() >= 128) {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    futures.clear();
                }
            }

            if (!futures.isEmpty()) {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
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

    public static List<BlockPos> scanSection(LevelChunkSection section, int sectionBottomY, int chunkMinX, int chunkMinZ, BlockArea scanArea, List<BlockBox> relevantBoxes) {
        List<BlockPos> found = new ArrayList<>();
        if (section == null || section.hasOnlyAir()) return found;
        if (!section.maybeHas(state -> state.is(Blocks.ANCIENT_DEBRIS))) return found;

        int topY = sectionBottomY + 15;
        boolean intersectsY = false;
        for (BlockBox box : relevantBoxes) {
            if (box.min().getY() <= topY && box.max().getY() >= sectionBottomY) {
                intersectsY = true;
                break;
            }
        }
        if (!intersectsY) return found;

        for (int localY = 0; localY < 16; localY++) {
            int y = sectionBottomY + localY;
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
        return found;
    }

    public static List<BlockPos> scanSerializableChunkData(SerializableChunkData data, ChunkPos chunkPos, BlockArea scanArea) {
        List<BlockPos> found = new ArrayList<>();
        if (data == null || scanArea == null) return found;

        List<BlockBox> relevantBoxes = scanArea.getBoxesIntersectingChunk(chunkPos);
        if (relevantBoxes.isEmpty()) return found;

        int chunkMinX = chunkPos.getMinBlockX();
        int chunkMinZ = chunkPos.getMinBlockZ();

        for (SerializableChunkData.SectionData sectionData : data.sectionData()) {
            int bottomY = sectionData.y() << 4;
            found.addAll(scanSection(sectionData.chunkSection(), bottomY, chunkMinX, chunkMinZ, scanArea, relevantBoxes));
        }
        return found;
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
            int bottomY = chunk.getMinY() + (sectionIndex << 4);
            found.addAll(scanSection(sections[sectionIndex], bottomY, chunkMinX, chunkMinZ, scanArea, relevantBoxes));
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
            recalculateQualifyingDebris();
        }
    }

    public static synchronized void recalculateQualifyingDebris() {
        int t = threshold;
        Set<BlockPos> newQualifying = new HashSet<>();

        if (axisMode == AxisMode.BOTH || axisMode == AxisMode.X_ONLY) {
            for (HashSet<BlockPos> set : xRows.values()) {
                if (set.size() > t) {
                    newQualifying.addAll(set);
                }
            }
        }

        if (axisMode == AxisMode.BOTH || axisMode == AxisMode.Z_ONLY) {
            for (HashSet<BlockPos> set : zRows.values()) {
                if (set.size() > t) {
                    newQualifying.addAll(set);
                }
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
                    if (pendingChunks.isEmpty()) {
                        reportScanStatus();
                    }
                });
            } else {
                List<BlockPos> debris = scanChunk(chunk, area);
                processChunkDebris(chunkPos, debris);
                if (pendingChunks.isEmpty()) {
                    reportScanStatus();
                }
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
                long pending = pendingChunks.size();
                String axisDesc = axisMode == AxisMode.BOTH ? "X/Z" : (axisMode == AxisMode.X_ONLY ? "X" : "Z");

                if (pending == 0) {
                    mc.player.sendSystemMessage(Component.literal(
                            String.format("[AreaScanner] Debris row scan complete: checked %d/%d chunks. Found %d debris in %d %s-rows (> %d)",
                                    scanned, allChunksCount, count, rows, axisDesc, threshold)
                    ).withColor(TextColor.fromRgb(0xFFA500)));

                    if (count > 0) {
                        mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1, 1);
                    }
                } else {
                    mc.player.sendSystemMessage(Component.literal(
                            String.format("[AreaScanner] Debris row scan: checked %d/%d loaded chunks (%d remaining). Move around to scan remaining chunks.",
                                    scanned, allChunksCount, pending)
                    ).withColor(TextColor.fromRgb(0xFFA500)));
                }
            });
        }
    }
}
