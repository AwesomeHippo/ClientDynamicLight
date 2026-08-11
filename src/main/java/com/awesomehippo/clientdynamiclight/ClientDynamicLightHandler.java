/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Vector3f;

@OnlyIn(Dist.CLIENT)
public enum ClientDynamicLightHandler {
    INSTANCE;

    //TODO: tweak these or add it to config.
    private static final int MAX_UPDATES_PER_TICK = 16; // 16 should definitely avoid lag spike
    private static final int LIGHT_CHANGE_THRESHOLD = 1;
    private static final int CLEANUP_TIMEOUT = 20;
    private static final int MAX_SCAN_RANGE = 64;
    private static final double MAX_DIST = 7.5D;
    private static final double MAX_DIST_SQ = 56.25D;
    private static final double INV_MAX_DIST = 1.0D / MAX_DIST;
    private static final double POSITION_CHANGE_THRESHOLD = 0.25D;
    private static final double POSITION_CHANGE_THRESHOLD_SQ = POSITION_CHANGE_THRESHOLD * POSITION_CHANGE_THRESHOLD;
    private static final int SMALL_SOURCE_COUNT = 8;
    private static final int ENTITY_SCAN_INTERVAL = 2;
    private static final int PACKED_FULL_BLOCK = 15 * 16;
    private static final int CELL_SHIFT = 3;
    private static final int WATER_OFFSET_MAX = 6;
    private static final double VIEW_RANGE_SECTION_MARGIN = 16.0D;
    private static final double BEHIND_CAMERA_SLACK = 1.0D;

    private static final ThreadLocal<Boolean> inDynamicLightComputation = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // may replace these maps with an unified WorldLightData class
    private final ConcurrentHashMap<Level, Map<Integer, DynamicLightSource>> worldLightsMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Level, Map<Long, List<DynamicLightSource>>> worldLightPositions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Level, Map<Long, List<DynamicLightSource>>> worldLightCells = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Level, Map<Long, Integer>> worldDynamicMaxLevels = new ConcurrentHashMap<>();
    private final Map<Long, Double> pendingSectionDirties = new HashMap<>();

    private final ThreadPoolExecutor executor;
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

    private volatile Level lastWorld;
    private volatile Map<Integer, DynamicLightSource> lastLightMap;
    private volatile Map<Long, List<DynamicLightSource>> lastLightPositions;
    private volatile Map<Long, List<DynamicLightSource>> lastLightCells;

    public boolean dynamicLightEnabled = true;

    private Level previousWorld = null;

    ClientDynamicLightHandler() {
        int cores = Runtime.getRuntime().availableProcessors();
        int maxThreads = Math.max(1, cores / 2);

        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "ClientDynamicLight-Scanner");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        };

        executor = new ThreadPoolExecutor(
            1,
            maxThreads,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            factory,
            new ThreadPoolExecutor.DiscardOldestPolicy()
        );
    }

    public void toggle() {
        dynamicLightEnabled = !dynamicLightEnabled;

        Minecraft mc = Minecraft.getInstance();
        if (!dynamicLightEnabled && mc.level != null) {
            cleanupWorldAddedLights(mc.level, true);
        }
    }

    // simple clean up (necessary when disabling the mod/leaving)
    private void cleanupWorldAddedLights(Level world, boolean relight) {
        if (world == null) {
            return;
        }

        Map<Long, List<DynamicLightSource>> lightPositions = worldLightPositions.get(world);
        if (lightPositions != null) {
            if (relight) {
                for (List<DynamicLightSource> sources : lightPositions.values()) {
                    for (DynamicLightSource source : sources) {
                        queueLightArea(source.renderX, source.renderY, source.renderZ);
                    }
                }
                flushRenderUpdates(world, true);
            }
            lightPositions.clear();
        }

        Map<Long, List<DynamicLightSource>> lightCells = worldLightCells.get(world);
        if (lightCells != null) {
            lightCells.clear();
        }

        Map<Integer, DynamicLightSource> lightMap = worldLightsMap.get(world);
        if (lightMap != null) {
            lightMap.clear();
        }

        Map<Long, Integer> maxLevels = worldDynamicMaxLevels.get(world);
        if (maxLevels != null) {
            maxLevels.clear();
        }
    }

    // main part running every tick to update lights
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Level world = mc.level;
        Player player = mc.player;

        // clean up on world change/unload to avoid potential issues
        if (world != previousWorld) {
            if (previousWorld != null) {
                cleanupWorldAddedLights(previousWorld, false);
                pendingSectionDirties.clear();
                executor.getQueue().clear();
            }
            previousWorld = world;
            lastWorld = null;
        }

        if (!dynamicLightEnabled) {
            if (world != null && !pendingSectionDirties.isEmpty()) {
                flushRenderUpdates(world, true);
            }
            return;
        }

        if (world == null || player == null || mc.screen != null) { // avoid running checks on menus
            return;
        }

        prunePendingOutsideView(world);
        scanEntitiesInRange(world, player);
        updateLightPositions(world);
        applyRenderUpdates(world, false);
    }

    private int getScanRange() {
        return (int) Math.min(getViewRangeBlocks(), MAX_SCAN_RANGE);
    }

    private double getViewRangeBlocks() {
        Minecraft mc = Minecraft.getInstance();
        int renderChunks = 12;
        if (mc.options != null) {
            renderChunks = mc.options.renderDistance().get();
        }
        return renderChunks * 16.0D + VIEW_RANGE_SECTION_MARGIN;
    }

    private double getViewRangeSq() {
        double range = getViewRangeBlocks();
        return range * range;
    }

    private void prunePendingOutsideView(Level world) {
        if (pendingSectionDirties.isEmpty()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        double rangeSq = getViewRangeSq();
        double camX = camPos.x;
        double camY = camPos.y;
        double camZ = camPos.z;

        Iterator<Long> it = pendingSectionDirties.keySet().iterator();
        while (it.hasNext()) {
            long key = it.next();
            int sx = SectionPos.x(key);
            int sy = SectionPos.y(key);
            int sz = SectionPos.z(key);
            if (!isSectionInViewRange(sx, sy, sz, camX, camY, camZ, rangeSq)) {
                it.remove();
            }
        }
    }

    private static boolean isSectionInViewRange(int sx, int sy, int sz, double camX, double camY, double camZ, double rangeSq) {
        double cx = SectionPos.sectionToBlockCoord(sx, 8);
        double cy = SectionPos.sectionToBlockCoord(sy, 8);
        double cz = SectionPos.sectionToBlockCoord(sz, 8);
        double dx = cx - camX;
        double dy = cy - camY;
        double dz = cz - camZ;
        return dx * dx + dy * dy + dz * dz <= rangeSq;
    }

    private static boolean isSectionVisibleToCamera(int sx, int sy, int sz, double camX, double camY, double camZ, Vector3f look) {
        double minX = SectionPos.sectionToBlockCoord(sx);
        double minY = SectionPos.sectionToBlockCoord(sy);
        double minZ = SectionPos.sectionToBlockCoord(sz);
        double maxX = minX + 16.0D;
        double maxY = minY + 16.0D;
        double maxZ = minZ + 16.0D;

        float lx = look.x();
        float ly = look.y();
        float lz = look.z();

        double x = lx >= 0.0F ? maxX : minX;
        double y = ly >= 0.0F ? maxY : minY;
        double z = lz >= 0.0F ? maxZ : minZ;
        double maxForward = (x - camX) * lx + (y - camY) * ly + (z - camZ) * lz;
        return maxForward >= -BEHIND_CAMERA_SLACK;
    }

    /* scan for entities that might emit light within range */
    private void scanEntitiesInRange(Level world, Player player) {
        int scanRange = getScanRange();
        boolean fullEntityScan = (world.getGameTime() % ENTITY_SCAN_INTERVAL) == 0L;
        AABB range = new AABB(
            player.getX() - scanRange, player.getY() - scanRange, player.getZ() - scanRange,
            player.getX() + scanRange, player.getY() + scanRange, player.getZ() + scanRange
        );
        List<Entity> entityList = fullEntityScan ? world.getEntitiesOfClass(Entity.class, range) : List.of();
        executor.execute(new ScannerRunnable(world, player, entityList, scanRange, fullEntityScan));
    }

    /* update/remove light sources, and queue updates */
    private void updateLightPositions(Level world) {
        Map<Integer, DynamicLightSource> lightMap = worldLightsMap.get(world);
        if (lightMap == null) {
            return;
        }

        long currentTime = world.getGameTime();
        Iterator<Map.Entry<Integer, DynamicLightSource>> it = lightMap.entrySet().iterator();
        Map<Long, List<DynamicLightSource>> lightPositions = worldLightPositions.get(world);
        Map<Long, List<DynamicLightSource>> lightCells = worldLightCells.get(world);

        while (it.hasNext()) {
            Map.Entry<Integer, DynamicLightSource> entry = it.next();
            DynamicLightSource source = entry.getValue();
            Entity entity = world.getEntity(entry.getKey());

            // gone entity
            if (entity == null || !entity.isAlive()) {
                source.targetLevel = 0;
            }

            boolean changed = source.tickUpdateLevel();
            if (changed) {
                long pos = packPosition(source.x, source.y, source.z);
                updateMaxAndQueue(world, pos, lightPositions);
            }

            if (source.level == 0 && source.targetLevel == 0) {
                // only now the level is at 0 so we can clean up
                long pos = packPosition(source.x, source.y, source.z);
                removeSourceFromIndexes(source, pos, lightPositions, lightCells);
                updateMaxAndQueue(world, pos, lightPositions);
                it.remove();
            } else if ((entity == null || !entity.isAlive()) && currentTime - source.lastSeen > CLEANUP_TIMEOUT) {
                // force clean up, even if level > 0, to prevent ghosts
                source.targetLevel = 0;
                source.level = 0;
                long pos = packPosition(source.x, source.y, source.z);
                removeSourceFromIndexes(source, pos, lightPositions, lightCells);
                updateMaxAndQueue(world, pos, lightPositions);
                it.remove();
            }
        }
    }

    /* calculate max light level at a position and queue updates (if necessary) */
    private void updateMaxAndQueue(Level world, long pos, Map<Long, List<DynamicLightSource>> lightPositions) {
        List<DynamicLightSource> sources = lightPositions == null ? null : lightPositions.get(pos);
        int newMax = 0;
        if (sources != null && !sources.isEmpty()) {
            for (DynamicLightSource source : sources) {
                newMax = Math.max(newMax, source.level);
            }
        }

        Map<Long, Integer> maxLevels = worldDynamicMaxLevels.computeIfAbsent(world, k -> new ConcurrentHashMap<>());
        int oldMax = maxLevels.getOrDefault(pos, 0);

        // only queue if the light level change is significant (may adjust LIGHT_CHANGE_THRESHOLD)
        if (Math.abs(newMax - oldMax) >= LIGHT_CHANGE_THRESHOLD) {
            int x = unpackX(pos);
            int y = unpackY(pos);
            int z = unpackZ(pos);
            queueLightArea(x + 0.5D, y + 0.5D, z + 0.5D);
            if (newMax == 0) {
                maxLevels.remove(pos);
            } else {
                maxLevels.put(pos, newMax);
            }
        }
    }

    private void queueLightArea(double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            return;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        double camX = camPos.x;
        double camY = camPos.y;
        double camZ = camPos.z;
        double rangeSq = getViewRangeSq();

        int minSx = SectionPos.blockToSectionCoord(Mth.floor(x - MAX_DIST));
        int maxSx = SectionPos.blockToSectionCoord(Mth.floor(x + MAX_DIST));
        int minSy = SectionPos.blockToSectionCoord(Mth.floor(y - MAX_DIST));
        int maxSy = SectionPos.blockToSectionCoord(Mth.floor(y + MAX_DIST));
        int minSz = SectionPos.blockToSectionCoord(Mth.floor(z - MAX_DIST));
        int maxSz = SectionPos.blockToSectionCoord(Mth.floor(z + MAX_DIST));

        for (int sx = minSx; sx <= maxSx; sx++) {
            for (int sy = minSy; sy <= maxSy; sy++) {
                for (int sz = minSz; sz <= maxSz; sz++) {
                    if (!isSectionInViewRange(sx, sy, sz, camX, camY, camZ, rangeSq)) {
                        continue;
                    }
                    double cx = SectionPos.sectionToBlockCoord(sx, 8);
                    double cy = SectionPos.sectionToBlockCoord(sy, 8);
                    double cz = SectionPos.sectionToBlockCoord(sz, 8);
                    double dx = cx - camX;
                    double dy = cy - camY;
                    double dz = cz - camZ;
                    double distSq = dx * dx + dy * dy + dz * dz;
                    long sectionKey = SectionPos.asLong(sx, sy, sz);
                    pendingSectionDirties.merge(sectionKey, distSq, Math::min);
                }
            }
        }
    }

    private void applyRenderUpdates(Level world, boolean force) {
        if (pendingSectionDirties.isEmpty()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer == null || mc.level == null || mc.level != world) {
            pendingSectionDirties.clear();
            return;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        double camX = camPos.x;
        double camY = camPos.y;
        double camZ = camPos.z;
        Vector3f look = camera.getLookVector();
        double rangeSq = getViewRangeSq();

        List<Map.Entry<Long, Double>> batch = new ArrayList<>(pendingSectionDirties.entrySet());
        batch.sort(Comparator.comparingDouble(Map.Entry::getValue));

        int count = 0;
        for (Map.Entry<Long, Double> entry : batch) {
            if (count >= MAX_UPDATES_PER_TICK) {
                break;
            }

            long key = entry.getKey();
            int sx = SectionPos.x(key);
            int sy = SectionPos.y(key);
            int sz = SectionPos.z(key);

            if (!force && !isSectionInViewRange(sx, sy, sz, camX, camY, camZ, rangeSq)) {
                pendingSectionDirties.remove(key);
                continue;
            }

            if (!force && !isSectionVisibleToCamera(sx, sy, sz, camX, camY, camZ, look)) {
                continue;
            }

            if (pendingSectionDirties.remove(key) == null) {
                continue;
            }

            mc.levelRenderer.setSectionDirty(sx, sy, sz);
            count++;
        }
    }

    private void flushRenderUpdates(Level world, boolean force) {
        while (!pendingSectionDirties.isEmpty()) {
            int before = pendingSectionDirties.size();
            applyRenderUpdates(world, force);
            if (!force) {
                break;
            }
            if (pendingSectionDirties.size() >= before) {
                pendingSectionDirties.clear();
                break;
            }
        }
    }

    public static boolean shouldSkipDynamicLight(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return state.getLightEmission(level, pos) > 0;
    }

    public static int applyDynamicLightToPacked(int packedLight, BlockPos pos) {
        if (!INSTANCE.dynamicLightEnabled) {
            return packedLight;
        }

        if ((packedLight & 255) >= PACKED_FULL_BLOCK) {
            return packedLight;
        }

        if (inDynamicLightComputation.get()) {
            return packedLight;
        }

        return mergeDynamicLight(computeDynamicLightLevelRaw(pos), packedLight);
    }

    public static int applyDynamicLightToPackedForEntity(Entity entity, int packedLight) {
        if (!INSTANCE.dynamicLightEnabled) {
            return packedLight;
        }

        if (inDynamicLightComputation.get()) {
            return packedLight;
        }

        double light = computeDynamicLightLevelRaw(entity.blockPosition());
        Player localPlayer = Minecraft.getInstance().player;
        if (entity == localPlayer) {
            DynamicLightSource source = getPlayerLightSource();
            if (source != null && source.level > 0) {
                light = Math.max(light, source.level);
            }
        }

        return mergeDynamicLight(light, packedLight);
    }

    private static DynamicLightSource getPlayerLightSource() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            return null;
        }

        Map<Integer, DynamicLightSource> lightMap = INSTANCE.worldLightsMap.get(level);
        if (lightMap == null) {
            return null;
        }

        return lightMap.get(player.getId());
    }

    private static int mergeDynamicLight(double dynamicLight, int packedLight) {
        if (dynamicLight <= 0.0D) {
            return packedLight;
        }

        int subBlock = (int) (dynamicLight * 16.0D);
        int blockSubLevel = packedLight & 255;
        if (subBlock <= blockSubLevel) {
            return packedLight;
        }

        return (packedLight & ~255) | subBlock;
    }

    public static double computeDynamicLightLevel(BlockPos pos) {
        if (!INSTANCE.dynamicLightEnabled) {
            return 0.0D;
        }

        if (inDynamicLightComputation.get()) {
            return 0.0D;
        }

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return 0.0D;
        }

        if (shouldSkipDynamicLight(level, pos, level.getBlockState(pos))) {
            return 0.0D;
        }

        return computeDynamicLightLevelRaw(pos);
    }

    private static double computeDynamicLightLevelRaw(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return 0.0D;
        }

        inDynamicLightComputation.set(true);
        try {
            if (level != INSTANCE.lastWorld || INSTANCE.lastLightMap == null) {
                INSTANCE.lastWorld = level;
                INSTANCE.lastLightMap = INSTANCE.worldLightsMap.get(level);
                INSTANCE.lastLightPositions = INSTANCE.worldLightPositions.get(level);
                INSTANCE.lastLightCells = INSTANCE.worldLightCells.get(level);
            }

            Map<Integer, DynamicLightSource> lightMap = INSTANCE.lastLightMap;
            if (lightMap == null || lightMap.isEmpty()) {
                return 0.0D;
            }

            double queryX = pos.getX() + 0.5D;
            double queryY = pos.getY() + 0.5D;
            double queryZ = pos.getZ() + 0.5D;

            if (lightMap.size() <= SMALL_SOURCE_COUNT) {
                return contributeFromSources(lightMap.values(), queryX, queryY, queryZ);
            }

            Map<Long, List<DynamicLightSource>> lightCells = INSTANCE.lastLightCells;
            if (lightCells != null && !lightCells.isEmpty()) {
                return contributeFromCells(lightCells, pos.getX(), pos.getY(), pos.getZ(), queryX, queryY, queryZ);
            }

            Map<Long, List<DynamicLightSource>> lightPosMap = INSTANCE.lastLightPositions;
            if (lightPosMap == null || lightPosMap.isEmpty()) {
                return contributeFromSources(lightMap.values(), queryX, queryY, queryZ);
            }

            double maxLight = 0.0D;
            int qbx = pos.getX();
            int qby = pos.getY();
            int qbz = pos.getZ();
            for (Map.Entry<Long, List<DynamicLightSource>> e : lightPosMap.entrySet()) {
                long packed = e.getKey();
                int sx = unpackX(packed);
                int sy = unpackY(packed);
                int sz = unpackZ(packed);
                if (Math.abs(sx - qbx) > 8 || Math.abs(sy - qby) > 8 || Math.abs(sz - qbz) > 8) {
                    continue;
                }
                maxLight = contributeFromSources(e.getValue(), queryX, queryY, queryZ, maxLight);
                if (maxLight >= 15.0D) {
                    return 15.0D;
                }
            }

            return maxLight;
        } finally {
            inDynamicLightComputation.set(false);
        }
    }

    private static double contributeFromCells(
        Map<Long, List<DynamicLightSource>> lightCells,
        int qbx,
        int qby,
        int qbz,
        double queryX,
        double queryY,
        double queryZ
    ) {
        int cellX = qbx >> CELL_SHIFT;
        int cellY = qby >> CELL_SHIFT;
        int cellZ = qbz >> CELL_SHIFT;
        double maxLight = 0.0D;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<DynamicLightSource> list = lightCells.get(packPosition(cellX + dx, cellY + dy, cellZ + dz));
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    maxLight = contributeFromSources(list, queryX, queryY, queryZ, maxLight);
                    if (maxLight >= 15.0D) {
                        return 15.0D;
                    }
                }
            }
        }

        return maxLight;
    }

    private static double contributeFromSources(Iterable<DynamicLightSource> sources, double queryX, double queryY, double queryZ) {
        return contributeFromSources(sources, queryX, queryY, queryZ, 0.0D);
    }

    private static double contributeFromSources(Iterable<DynamicLightSource> sources, double queryX, double queryY, double queryZ, double maxLight) {
        for (DynamicLightSource source : sources) {
            if (source.level <= 0 || source.level <= maxLight) {
                continue;
            }
            double dx = queryX - source.renderX;
            double dy = queryY - source.renderY;
            double dz = queryZ - source.renderZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > MAX_DIST_SQ) {
                continue;
            }
            double propagated = (1.0D - Math.sqrt(distSq) * INV_MAX_DIST) * source.level;
            if (propagated > maxLight) {
                maxLight = propagated;
                if (maxLight >= 15.0D) {
                    return 15.0D;
                }
            }
        }
        return maxLight;
    }

    private static long packPosition(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (long) (z & 0x3FFFFFF);
    }

    // better w/ separate X/Y/Z unpacks so we don't allocate int[] every time actually
    private static int unpackX(long packed) {
        int x = (int) (packed >> 38);
        if ((x & 0x2000000) != 0) {
            x |= ~0x3FFFFFF;
        }
        return x;
    }

    private static int unpackY(long packed) {
        int y = (int) ((packed >> 26) & 0xFFF);
        if ((y & 0x800) != 0) {
            y |= ~0xFFF;
        }
        return y;
    }

    private static int unpackZ(long packed) {
        int z = (int) (packed & 0x3FFFFFF);
        if ((z & 0x2000000) != 0) {
            z |= ~0x3FFFFFF;
        }
        return z;
    }

    private static long packCell(int blockX, int blockY, int blockZ) {
        return packPosition(blockX >> CELL_SHIFT, blockY >> CELL_SHIFT, blockZ >> CELL_SHIFT);
    }

    private static void addSourceToIndexes(DynamicLightSource source, long pos, Map<Long, List<DynamicLightSource>> lightPositions, Map<Long, List<DynamicLightSource>> lightCells) {
        lightPositions.computeIfAbsent(pos, k -> new ArrayList<>(2)).add(source);
        long cell = packCell(source.x, source.y, source.z);
        lightCells.computeIfAbsent(cell, k -> new ArrayList<>(2)).add(source);
    }

    private static void removeSourceFromIndexes(DynamicLightSource source, long pos, Map<Long, List<DynamicLightSource>> lightPositions, Map<Long, List<DynamicLightSource>> lightCells) {
        if (lightPositions != null) {
            List<DynamicLightSource> list = lightPositions.get(pos);
            if (list != null) {
                list.remove(source);
                if (list.isEmpty()) {
                    lightPositions.remove(pos);
                }
            }
        }

        if (lightCells != null) {
            long cell = packCell(source.x, source.y, source.z);
            List<DynamicLightSource> cellList = lightCells.get(cell);
            if (cellList != null) {
                cellList.remove(source);
                if (cellList.isEmpty()) {
                    lightCells.remove(cell);
                }
            }
        }
    }

    // holds the info for a light source
    private static class DynamicLightSource {
        int x, y, z;
        double renderX, renderY, renderZ;
        int level;
        int targetLevel;
        long lastSeen;
        boolean playerSource;

        DynamicLightSource(int x, int y, int z, double renderX, double renderY, double renderZ, int level, boolean playerSource) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.renderX = renderX;
            this.renderY = renderY;
            this.renderZ = renderZ;
            this.level = level;
            this.targetLevel = level;
            this.lastSeen = 0;
            this.playerSource = playerSource;
        }

        boolean hasMovedSignificantly(double newRenderX, double newRenderY, double newRenderZ) {
            double dx = newRenderX - renderX;
            double dy = newRenderY - renderY;
            double dz = newRenderZ - renderZ;
            return dx * dx + dy * dy + dz * dz > POSITION_CHANGE_THRESHOLD_SQ;
        }

        // for smoother transition
        public boolean tickUpdateLevel() {
            if (level == targetLevel) {
                return false;
            }
            if (level < targetLevel) {
                level++;
            } else {
                level--;
            }
            return true;
        }
    }

    private static class ScannerRunnable implements Runnable {
        private final Level world;
        private final Player player;
        private final List<Entity> entityList;
        private final int scanRange;
        private final boolean fullEntityScan;

        ScannerRunnable(Level world, Player player, List<Entity> entityList, int scanRange, boolean fullEntityScan) {
            this.world = world;
            this.player = player;
            this.entityList = entityList;
            this.scanRange = scanRange;
            this.fullEntityScan = fullEntityScan;
        }

        @Override
        public void run() {
            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();
            double rangeSq = (double) scanRange * scanRange;

            List<Entity> entities = new ArrayList<>();
            if (fullEntityScan) {
                for (Entity entity : entityList) {
                    // filter entities within range and skip player
                    if (entity == player) {
                        continue;
                    }
                    double ex = entity.getX() - px;
                    double ey = entity.getY() - py;
                    double ez = entity.getZ() - pz;
                    if (ex * ex + ey * ey + ez * ez > rangeSq) {
                        continue;
                    }
                    entities.add(entity);
                }
            }

            // run light updates
            Minecraft.getInstance().execute(() -> {
                Map<Integer, DynamicLightSource> lightMap = INSTANCE.worldLightsMap.computeIfAbsent(world, k -> new ConcurrentHashMap<>());
                Map<Long, List<DynamicLightSource>> lightPositions = INSTANCE.worldLightPositions.computeIfAbsent(world, k -> new ConcurrentHashMap<>());
                Map<Long, List<DynamicLightSource>> lightCells = INSTANCE.worldLightCells.computeIfAbsent(world, k -> new ConcurrentHashMap<>());

                Map<Integer, Integer> seenLightLevels = new HashMap<>();
                Map<Integer, double[]> seenPos = new HashMap<>();

                // player's lighting
                int playerLightLevel = EntityLightLevelHelper.getLightLevel(world, player);
                boolean playerTracked = lightMap.containsKey(player.getId());
                if (playerLightLevel >= 0 || playerTracked) {
                    seenLightLevels.put(player.getId(), playerLightLevel < 0 ? 0 : playerLightLevel);
                    seenPos.put(player.getId(), new double[] { player.getX(), player.getY(), player.getZ() });
                }

                if (fullEntityScan) {
                    // then handle other entities
                    for (Entity entity : entities) {
                        int lightLevel = EntityLightLevelHelper.getLightLevel(world, entity);
                        boolean tracked = lightMap.containsKey(entity.getId());

                        if (lightLevel < 0) { // skip if the helper indicates to skip
                            if (tracked) {
                                seenLightLevels.put(entity.getId(), 0);
                                seenPos.put(entity.getId(), new double[] { entity.getX(), entity.getY(), entity.getZ() });
                            }
                            continue;
                        }

                        if (lightLevel > 0 || tracked) {
                            seenLightLevels.put(entity.getId(), lightLevel);
                            seenPos.put(entity.getId(), new double[] { entity.getX(), entity.getY(), entity.getZ() });
                        }
                    }

                    Set<Integer> currentKeys = new HashSet<>(lightMap.keySet());
                    for (Integer id : currentKeys) {
                        if (!seenLightLevels.containsKey(id)) {
                            DynamicLightSource source = lightMap.get(id);
                            if (source != null) {
                                source.targetLevel = 0;
                            }
                        }
                    }
                }

                // update sources for seen entities
                for (Map.Entry<Integer, Integer> entry : seenLightLevels.entrySet()) {
                    int id = entry.getKey();
                    int level = entry.getValue();
                    double[] position = seenPos.get(id);
                    Entity entity = id == player.getId() ? player : world.getEntity(id);
                    updateLightSource(world, id, entity, position[0], position[1], position[2], level, lightMap, lightPositions, lightCells);
                }

                // transfer check for sources that need to increase light level
                //TODO: this may be optimizable
                for (Map.Entry<Integer, Integer> entry : seenLightLevels.entrySet()) {
                    DynamicLightSource source = lightMap.get(entry.getKey());
                    if (source == null || source.level >= source.targetLevel) {
                        continue;
                    }
                    int maxFading = 0;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                List<DynamicLightSource> list = lightPositions.get(packPosition(source.x + dx, source.y + dy, source.z + dz));
                                if (list == null) {
                                    continue;
                                }
                                for (DynamicLightSource neighbor : list) {
                                    if (neighbor.targetLevel <= 0 && neighbor.level > 0) {
                                        maxFading = Math.max(maxFading, neighbor.level);
                                    }
                                }
                            }
                        }
                    }
                    if (maxFading > source.level) {
                        source.level = maxFading;
                        long pos = packPosition(source.x, source.y, source.z);
                        INSTANCE.updateMaxAndQueue(world, pos, lightPositions);
                    }
                }
            });
        }
    }

    // special case to prevent some strange glitches in water
    private static boolean resolveLightBlock(Level world, BlockPos.MutableBlockPos pos) {
        BlockState currentState = world.getBlockState(pos);
        if (!currentState.getFluidState().is(FluidTags.WATER)) {
            return true; // (not water so it's fine)
        }

        int bx = pos.getX();
        int by = pos.getY();
        int bz = pos.getZ();

        // search for solid/non-water block below first
        for (int dy = 1; dy <= WATER_OFFSET_MAX; dy++) {
            int offsetY = by - dy;
            if (offsetY < world.getMinBuildHeight()) {
                break;
            }

            pos.set(bx, offsetY, bz);
            BlockState state = world.getBlockState(pos);
            if (!state.getFluidState().is(FluidTags.WATER) && !state.isAir()) { // (solid blocks)
                return true;
            }
        }

        // air below, if there is?
        for (int dy = 1; dy <= WATER_OFFSET_MAX; dy++) {
            int offsetY = by - dy;
            if (offsetY < world.getMinBuildHeight()) {
                break;
            }

            pos.set(bx, offsetY, bz);
            if (world.getBlockState(pos).isAir()) {
                return true;
            }
        }

        pos.set(bx, by, bz);
        return false;
    }

    /* update/create light source for an entity */
    private static void updateLightSource(
        Level world,
        int entityId,
        Entity entity,
        double x,
        double y,
        double z,
        int level,
        Map<Integer, DynamicLightSource> lightMap,
        Map<Long, List<DynamicLightSource>> lightPositions,
        Map<Long, List<DynamicLightSource>> lightCells
    ) {
        int bx = Mth.floor(x);
        int by = Mth.floor(y);
        int bz = Mth.floor(z);

        // special case if in water to avoid some kind of glitch
        BlockPos.MutableBlockPos scratch = INSTANCE.scratchPos.set(bx, by, bz);
        if (!resolveLightBlock(world, scratch)) {
            level = 0;
            bx = Mth.floor(x);
            by = Mth.floor(y);
            bz = Mth.floor(z);
        } else {
            bx = scratch.getX();
            by = scratch.getY();
            bz = scratch.getZ();
        }

        double renderX = x - 0.5D;
        double renderY = y - 0.5D;
        double renderZ = z - 0.5D;
        if (entity instanceof LivingEntity living) {
            renderY += living.getEyeHeight();
        }

        DynamicLightSource source = lightMap.get(entityId);
        long newPos = packPosition(bx, by, bz);

        Player localPlayer = Minecraft.getInstance().player;
        boolean isPlayer = localPlayer != null && localPlayer.getId() == entityId;
        if (level <= 0 && source == null && !isPlayer) { // skip if no light and no existing source
            return;
        }

        int targetted = Math.max(level, 0);

        if (source == null) {
            source = new DynamicLightSource(bx, by, bz, renderX, renderY, renderZ, 0, isPlayer);
            source.targetLevel = targetted;
            lightMap.put(entityId, source);
            addSourceToIndexes(source, newPos, lightPositions, lightCells);
            INSTANCE.updateMaxAndQueue(world, newPos, lightPositions);
            INSTANCE.queueLightArea(renderX, renderY, renderZ);
        } else {
            long oldPos = packPosition(source.x, source.y, source.z);
            boolean movedBlock = oldPos != newPos;
            boolean movedRender = source.hasMovedSignificantly(renderX, renderY, renderZ);
            boolean targetChanged = source.targetLevel != targetted;

            double oldRenderX = source.renderX;
            double oldRenderY = source.renderY;
            double oldRenderZ = source.renderZ;

            if (movedBlock) { // entity moved, update position
                removeSourceFromIndexes(source, oldPos, lightPositions, lightCells);
                INSTANCE.updateMaxAndQueue(world, oldPos, lightPositions);
                source.x = bx;
                source.y = by;
                source.z = bz;
                addSourceToIndexes(source, newPos, lightPositions, lightCells);
                INSTANCE.updateMaxAndQueue(world, newPos, lightPositions);
            }

            source.renderX = renderX;
            source.renderY = renderY;
            source.renderZ = renderZ;

            if (targetChanged) {
                source.targetLevel = targetted;
            }

            if (movedBlock) {
                INSTANCE.queueLightArea(oldRenderX, oldRenderY, oldRenderZ);
                INSTANCE.queueLightArea(renderX, renderY, renderZ);
            } else if (movedRender) {
                INSTANCE.queueLightArea(renderX, renderY, renderZ);
            }
        }

        source.lastSeen = world.getGameTime();
    }

    // getter for config
    public boolean isEnabled() {
        return dynamicLightEnabled;
    }

}
