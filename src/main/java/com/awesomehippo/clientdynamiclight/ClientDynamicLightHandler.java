/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockAndTintGetter;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@OnlyIn(Dist.CLIENT)
public enum ClientDynamicLightHandler {
    INSTANCE;

    private static final int MAX_UPDATES_PER_TICK = 16; // 16 should definitely avoid lag spike
    private static final int LIGHT_CHANGE_THRESHOLD = 1;
    private static final int CLEANUP_TIMEOUT = 20;
    private static final int MAX_SCAN_RANGE = 64;
    private static final double MAX_DIST = 7.5D;
    private static final double MAX_DIST_SQ = 56.25D;
    private static final double POSITION_CHANGE_THRESHOLD = 0.1D;
    private static final double POSITION_CHANGE_THRESHOLD_SQ = POSITION_CHANGE_THRESHOLD * POSITION_CHANGE_THRESHOLD;
    private static final int NEAR_PLACED_LIGHT_RADIUS = 2;
    private static final int PACKED_LIGHT_SKIP_THRESHOLD = 11 * 16;

    private static final ThreadLocal<Boolean> inDynamicLightComputation = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // may replace these maps with an unified WorldLightData class
    private final ConcurrentHashMap<Level, Map<Integer, DynamicLightSource>> worldLightsMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Level, Map<Long, List<DynamicLightSource>>> worldLightPositions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Level, Map<Long, Integer>> worldDynamicMaxLevels = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<UpdateEntry> pendingRenderUpdates = new PriorityBlockingQueue<>();

    private final ThreadPoolExecutor executor;

    private volatile Level lastWorld;
    private volatile Map<Integer, DynamicLightSource> lastLightMap;
    private volatile Map<Long, List<DynamicLightSource>> lastLightPositions;
    private volatile Map<Long, Integer> lastMaxLevels;

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
                for (long packed : lightPositions.keySet()) {
                    int[] c = unpackPosition(packed);
                    requestRelight(world, c[0], c[1], c[2]);
                }
            }
            lightPositions.clear();
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
        if (!dynamicLightEnabled || event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Level world = mc.level;
        Player player = mc.player;

        // clean up on world change/unload to avoid potential issues
        if (world != previousWorld) {
            if (previousWorld != null) {
                cleanupWorldAddedLights(previousWorld, false);
                pendingRenderUpdates.clear();
                executor.getQueue().clear();
            }
            previousWorld = world;
            lastWorld = null;
        }

        if (world == null || player == null || mc.screen != null) { // avoid running checks on menus
            return;
        }

        scanEntitiesInRange(world, player);
        updateLightPositions(world);
        applyRenderUpdates(world);
    }

    private int getScanRange() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) {
            return MAX_SCAN_RANGE;
        }

        return Math.min(mc.options.renderDistance().get() * 16, MAX_SCAN_RANGE);
    }

    /* scan for entities that might emit light within range */
    private void scanEntitiesInRange(Level world, Player player) {
        int scanRange = getScanRange();
        AABB range = new AABB(
            player.getX() - scanRange, player.getY() - scanRange, player.getZ() - scanRange,
            player.getX() + scanRange, player.getY() + scanRange, player.getZ() + scanRange
        );
        List<Entity> entityList = world.getEntitiesOfClass(Entity.class, range);
        executor.execute(new ScannerRunnable(world, player, entityList, scanRange));
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

        while (it.hasNext()) {
            Map.Entry<Integer, DynamicLightSource> entry = it.next();
            DynamicLightSource source = entry.getValue();
            Entity entity = world.getEntity(entry.getKey());

            // gone entity
            if (entity == null || !entity.isAlive()) {
                source.targetLevel = 0;
            }

            if (source.playerSource && source.level > 0 && source.targetLevel <= 0
                && isNearPlacedLight(world, source.x, source.y, source.z, source.level)) {
                source.level = 0;
                source.targetLevel = 0;
                source.litAreaStable = true;
            }

            boolean changed;
            if (source.litAreaStable) {
                if (source.level != source.targetLevel) {
                    source.level = source.targetLevel;
                    changed = true;
                } else {
                    changed = false;
                }
            } else {
                changed = source.tickUpdateLevel();
            }
            if (changed) {
                long pos = packPosition(source.x, source.y, source.z);
                updateMaxAndQueue(world, pos, lightPositions);
            }

            if (source.level == 0 && source.targetLevel == 0) {
                // only now the level is at 0 so we can clean up
                long pos = packPosition(source.x, source.y, source.z);

                if (lightPositions != null) {
                    List<DynamicLightSource> list = lightPositions.get(pos);
                    if (list != null) {
                        list.remove(source);
                        if (list.isEmpty()) {
                            lightPositions.remove(pos);
                        }
                    }
                }
                updateMaxAndQueue(world, pos, lightPositions);
                it.remove();
            } else if ((entity == null || !entity.isAlive()) && currentTime - source.lastSeen > CLEANUP_TIMEOUT) {
                // force clean up, even if level > 0, to prevent ghosts
                source.targetLevel = 0;
                source.level = 0;
                long pos = packPosition(source.x, source.y, source.z);
                if (lightPositions != null) {
                    List<DynamicLightSource> list = lightPositions.get(pos);
                    if (list != null) {
                        list.remove(source);
                        if (list.isEmpty()) {
                            lightPositions.remove(pos);
                        }
                    }
                }
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
            int[] c = unpackPosition(pos);
            queueRenderUpdate(c[0], c[1], c[2]);
            if (newMax == 0) {
                maxLevels.remove(pos);
            } else {
                maxLevels.put(pos, newMax);
            }
        }
    }

    private void queueRenderUpdate(int x, int y, int z) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        long pos = packPosition(x, y, z);
        double dx = x - player.getX();
        double dy = y - player.getY();
        double dz = z - player.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;
        pendingRenderUpdates.add(new UpdateEntry(pos, distSq));
    }

    private void applyRenderUpdates(Level world) {
        if (pendingRenderUpdates.isEmpty()) {
            return;
        }

        int count = 0;
        while (!pendingRenderUpdates.isEmpty() && count < MAX_UPDATES_PER_TICK) {
            UpdateEntry entry = pendingRenderUpdates.poll();
            int[] c = unpackPosition(entry.pos);
            requestRelight(world, c[0], c[1], c[2]);
            count++;
        }
    }

    private static void requestRelight(Level world, int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer == null || mc.level == null || mc.level != world) {
            return;
        }

        BlockPos pos = new BlockPos(x, y, z);
        if (shouldSkipDynamicLight(world, pos, world.getBlockState(pos))) {
            return;
        }

        int sectionX = SectionPos.blockToSectionCoord(x);
        int sectionY = SectionPos.blockToSectionCoord(y);
        int sectionZ = SectionPos.blockToSectionCoord(z);
        mc.levelRenderer.setSectionDirtyWithNeighbors(sectionX, sectionY, sectionZ);
    }

    public static boolean shouldSkipDynamicLight(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return state.getLightEmission(level, pos) > 0;
    }

    private static boolean hasEmissiveBlockNearby(Level world, int x, int y, int z, int radius) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    probe.set(x + dx, y + dy, z + dz);
                    if (world.getBlockState(probe).getLightEmission(world, probe) > 0) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean isNearPlacedLight(Level world, int x, int y, int z, int referenceLevel) {
        // emissive block probe only - getBrightness goes through our mixin and messes up the check
        return hasEmissiveBlockNearby(world, x, y, z, NEAR_PLACED_LIGHT_RADIUS);
    }

    private static void applyLightLevelPolicy(
        DynamicLightSource source,
        Level world,
        int x,
        int y,
        int z,
        int targetLevel,
        boolean isPlayer
    ) {
        if (!isPlayer) {
            source.litAreaStable = false;
            return;
        }

        if (targetLevel <= 0) {
            if (isNearPlacedLight(world, x, y, z, source.level)) {
                source.litAreaStable = true;
                source.level = 0;
                source.targetLevel = 0;
            } else {
                source.litAreaStable = false;
            }
            return;
        }

        if (isNearPlacedLight(world, x, y, z, targetLevel)) {
            // near placed light, vanilla handles it - don't stack weaker held-item light on top
            source.litAreaStable = true;
            source.level = 0;
            source.targetLevel = 0;
        } else {
            source.litAreaStable = false;
        }
    }

    public static int getDynamicLightLevel(BlockPos pos, int vanilla) {
        if (!INSTANCE.dynamicLightEnabled) {
            return vanilla;
        }

        if (inDynamicLightComputation.get()) {
            return vanilla;
        }

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level != null && shouldSkipDynamicLight(level, pos, level.getBlockState(pos))) {
            return vanilla;
        }

        if (vanilla >= 15) {
            return vanilla;
        }

        inDynamicLightComputation.set(true);
        try {
            return Math.max(vanilla, Mth.ceil(computeDynamicLightLevel(pos)));
        } finally {
            inDynamicLightComputation.set(false);
        }
    }

    public static int applyDynamicLightToPacked(int packedLight, BlockPos pos) {
        if (!INSTANCE.dynamicLightEnabled) {
            return packedLight;
        }

        if ((packedLight & 255) >= PACKED_LIGHT_SKIP_THRESHOLD) {
            return packedLight;
        }

        double dyn = inDynamicLightComputation.get() ? 0.0D : computeDynamicLightLevel(pos);
        return mergeDynamicLight(dyn, packedLight);
    }

    public static int applyDynamicLightToPackedForEntity(Entity entity, int packedLight) {
        if (!INSTANCE.dynamicLightEnabled) {
            return packedLight;
        }

        double light = inDynamicLightComputation.get() ? 0.0D : computeDynamicLightLevel(entity.blockPosition());
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

        inDynamicLightComputation.set(true);
        try {
            if (!level.equals(INSTANCE.lastWorld) || INSTANCE.lastLightMap == null) {
                INSTANCE.lastWorld = level;
                INSTANCE.lastLightMap = INSTANCE.worldLightsMap.get(level);
                INSTANCE.lastLightPositions = INSTANCE.worldLightPositions.get(level);
                INSTANCE.lastMaxLevels = INSTANCE.worldDynamicMaxLevels.get(level);
            }

            Map<Integer, DynamicLightSource> lightMap = INSTANCE.lastLightMap;
            if (lightMap == null || lightMap.isEmpty()) {
                return 0.0D;
            }

            double queryX = pos.getX();
            double queryY = pos.getY();
            double queryZ = pos.getZ();
            double maxLight = 0.0D;

            Map<Long, Integer> maxLvls = INSTANCE.lastMaxLevels;
            if (maxLvls != null) {
                long qp = packPosition(pos.getX(), pos.getY(), pos.getZ());
                Integer direct = maxLvls.get(qp);
                if (direct != null && direct > maxLight) {
                    maxLight = direct;
                }
            }

            Map<Long, List<DynamicLightSource>> lightPosMap = INSTANCE.lastLightPositions;
            boolean usedSpatial = false;
            if (lightPosMap != null && !lightPosMap.isEmpty()) {
                usedSpatial = true;
                int qbx = pos.getX();
                int qby = pos.getY();
                int qbz = pos.getZ();
                for (Map.Entry<Long, List<DynamicLightSource>> e : lightPosMap.entrySet()) {
                    long sp = e.getKey();
                    int[] s = unpackPosition(sp);
                    int dxb = Math.abs(s[0] - qbx);
                    int dyb = Math.abs(s[1] - qby);
                    int dzb = Math.abs(s[2] - qbz);
                    if (dxb > 8 || dyb > 8 || dzb > 8) {
                        continue;
                    }
                    for (DynamicLightSource source : e.getValue()) {
                        if (source.level <= 0) {
                            continue;
                        }
                        double srcLevel = source.level;
                        if (srcLevel <= maxLight) {
                            continue;
                        }
                        double dx = queryX - source.renderX;
                        double dy = queryY - source.renderY;
                        double dz = queryZ - source.renderZ;
                        double distSq = dx * dx + dy * dy + dz * dz;
                        if (distSq > MAX_DIST_SQ) {
                            continue;
                        }
                        double dist = Math.sqrt(distSq);
                        double falloff = 1.0D - dist / MAX_DIST;
                        double propagated = falloff * srcLevel;
                        if (propagated > maxLight) {
                            maxLight = propagated;
                            if (maxLight >= 15.0D) {
                                return 15.0D;
                            }
                        }
                    }
                }
            }

            if (!usedSpatial) {
                for (DynamicLightSource source : lightMap.values()) {
                    if (source.level <= 0) {
                        continue;
                    }
                    double srcLevel = source.level;
                    if (srcLevel <= maxLight) {
                        continue;
                    }
                    double dx = queryX - source.renderX;
                    double dy = queryY - source.renderY;
                    double dz = queryZ - source.renderZ;
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > MAX_DIST_SQ) {
                        continue;
                    }
                    double dist = Math.sqrt(distSq);
                    double falloff = 1.0D - dist / MAX_DIST;
                    double propagated = falloff * srcLevel;
                    if (propagated > maxLight) {
                        maxLight = propagated;
                        if (maxLight >= 15.0D) {
                            return 15.0D;
                        }
                    }
                }
            }

            return Mth.clamp(maxLight, 0.0D, 15.0D);
        } finally {
            inDynamicLightComputation.set(false);
        }
    }

    private static long packPosition(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (long) (z & 0x3FFFFFF);
    }

    private static int[] unpackPosition(long packed) {
        int x = (int) (packed >> 38);
        int y = (int) ((packed >> 26) & 0xFFF);
        int z = (int) (packed & 0x3FFFFFF);
        if ((x & 0x2000000) != 0) {
            x |= ~0x3FFFFFF;
        }
        if ((z & 0x2000000) != 0) {
            z |= ~0x3FFFFFF;
        }

        return new int[] { x, y, z };
    }

    // holds the info for a light source
    private static class DynamicLightSource {
        int x, y, z;
        double renderX, renderY, renderZ;
        int level;
        int targetLevel;
        long lastSeen;
        boolean litAreaStable;
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

    /* queue entry for light updates (sorted by distance currently) - may move this and DynamicLightSource to another files */
    private static class UpdateEntry implements Comparable<UpdateEntry> {
        final long pos;
        final double distSq;

        UpdateEntry(long pos, double distSq) {
            this.pos = pos;
            this.distSq = distSq;
        }

        @Override
        public int compareTo(UpdateEntry other) { // closer updates first!
            return Double.compare(distSq, other.distSq);
        }
    }

    private static class ScannerRunnable implements Runnable {
        private final Level world;
        private final Player player;
        private final List<Entity> entityList;
        private final int scanRange;

        ScannerRunnable(Level world, Player player, List<Entity> entityList, int scanRange) {
            this.world = world;
            this.player = player;
            this.entityList = entityList;
            this.scanRange = scanRange;
        }

        @Override
        public void run() {
            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();
            double rangeSq = (double) scanRange * scanRange;

            List<Entity> entities = new ArrayList<>();

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

            // run light updates
            Minecraft.getInstance().execute(() -> {
                Map<Integer, DynamicLightSource> lightMap = INSTANCE.worldLightsMap.computeIfAbsent(world, k -> new ConcurrentHashMap<>());
                Map<Long, List<DynamicLightSource>> lightPositions = INSTANCE.worldLightPositions.computeIfAbsent(world, k -> new ConcurrentHashMap<>());

                Map<Integer, Integer> seenLightLevels = new HashMap<>();
                Map<Integer, double[]> seenPos = new HashMap<>();

                // player's lighting
                int playerLightLevel = EntityLightLevelHelper.getLightLevel(world, player);
                boolean playerTracked = lightMap.containsKey(player.getId());
                if (playerLightLevel >= 0 || playerTracked) {
                    seenLightLevels.put(player.getId(), playerLightLevel < 0 ? 0 : playerLightLevel);
                    seenPos.put(player.getId(), new double[] { player.getX(), player.getY(), player.getZ() });
                }

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
                            if (source.playerSource) {
                                applyLightLevelPolicy(source, world, source.x, source.y, source.z, 0, true);
                            } else {
                                source.litAreaStable = false;
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
                    updateLightSource(world, id, entity, position[0], position[1], position[2], level, lightMap, lightPositions);
                }

                // transfer check for sources that need to increase light level
                //TODO: this may be optimizable
                for (Map.Entry<Integer, Integer> entry : seenLightLevels.entrySet()) {
                    int id = entry.getKey();
                    DynamicLightSource source = lightMap.get(id);
                    if (source != null && source.level < source.targetLevel) {
                        long pos = packPosition(source.x, source.y, source.z);
                        int[] coord = unpackPosition(pos);
                        int maxFading = 0;
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dy = -1; dy <= 1; dy++) {
                                for (int dz = -1; dz <= 1; dz++) {
                                    long neighborPos = packPosition(coord[0] + dx, coord[1] + dy, coord[2] + dz);
                                    List<DynamicLightSource> list = lightPositions.get(neighborPos);
                                    if (list != null) {
                                        for (DynamicLightSource neighbor : list) {
                                            if (neighbor.targetLevel <= 0 && neighbor.level > 0) {
                                                maxFading = Math.max(maxFading, neighbor.level);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (maxFading > source.level) {
                            source.level = maxFading;
                            INSTANCE.updateMaxAndQueue(world, pos, lightPositions);
                        }
                    }
                }
            });
        }
    }

    // special case to prevent some strange glitches in water
    private static int[] findSpecialOffsetPosition(Level world, int bx, int by, int bz) {
        BlockState currentState = world.getBlockState(new BlockPos(bx, by, bz));
        int maxOffsetDistance = 6;

        if (!currentState.getFluidState().is(FluidTags.WATER)) {
            return new int[] { bx, by, bz }; // (not water so it's fine)
        }

        // search for solid/non-water block below first
        for (int dy = 1; dy <= maxOffsetDistance; dy++) {
            int offsetY = by - dy;
            if (offsetY < 0) {
                break;
            }

            BlockState state = world.getBlockState(new BlockPos(bx, offsetY, bz));
            if (!state.getFluidState().is(FluidTags.WATER) && !state.isAir()) { // (solid blocks)
                return new int[] { bx, offsetY, bz };
            }
        }

        // air below, if there is?
        for (int dy = 1; dy <= maxOffsetDistance; dy++) {
            int offsetY = by - dy;
            if (offsetY < 0) {
                break;
            }

            BlockState state = world.getBlockState(new BlockPos(bx, offsetY, bz));
            if (state.isAir()) {
                return new int[] { bx, offsetY, bz };
            }
        }

        return null;
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
        Map<Long, List<DynamicLightSource>> lightPositions
    ) {
        int bx = Mth.floor(x);
        int by = Mth.floor(y);
        int bz = Mth.floor(z);

        // special case if in water to avoid some kind of glitch
        int[] offsetPos = findSpecialOffsetPosition(world, bx, by, bz);
        if (offsetPos == null) {
            level = 0;
        } else {
            bx = offsetPos[0];
            by = offsetPos[1];
            bz = offsetPos[2];
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

        if (source == null) {
            source = new DynamicLightSource(bx, by, bz, renderX, renderY, renderZ, 0, isPlayer);
            source.targetLevel = level;
            applyLightLevelPolicy(source, world, bx, by, bz, level, isPlayer);
            lightMap.put(entityId, source);

            List<DynamicLightSource> list = lightPositions.computeIfAbsent(newPos, k -> new ArrayList<>());
            list.add(source);

            INSTANCE.updateMaxAndQueue(world, newPos, lightPositions);
            if (level > 0) {
                INSTANCE.queueRenderUpdate(bx, by, bz);
            }
        } else {
            long oldPos = packPosition(source.x, source.y, source.z);
            boolean movedBlock = oldPos != newPos;
            boolean movedRender = source.hasMovedSignificantly(renderX, renderY, renderZ);
            boolean levelChanged = source.targetLevel != level;

            if (movedBlock) { // entity moved, update position
                List<DynamicLightSource> oldList = lightPositions.get(oldPos);
                if (oldList != null) {
                    oldList.remove(source);
                    if (oldList.isEmpty()) {
                        lightPositions.remove(oldPos);
                    }
                }
                INSTANCE.updateMaxAndQueue(world, oldPos, lightPositions);
                source.x = bx;
                source.y = by;
                source.z = bz;
                List<DynamicLightSource> newList = lightPositions.computeIfAbsent(newPos, k -> new ArrayList<>());
                newList.add(source);
                INSTANCE.updateMaxAndQueue(world, newPos, lightPositions);
            }

            source.renderX = renderX;
            source.renderY = renderY;
            source.renderZ = renderZ;

            if (levelChanged) {
                source.targetLevel = level; // then update target light level
                applyLightLevelPolicy(source, world, source.x, source.y, source.z, level, isPlayer);
            }

            if (movedBlock || movedRender || levelChanged) {
                INSTANCE.queueRenderUpdate(source.x, source.y, source.z);
            }
        }

        source.lastSeen = world.getGameTime();
    }

    // getter for config
    public boolean isEnabled() {
        return dynamicLightEnabled;
    }

}
