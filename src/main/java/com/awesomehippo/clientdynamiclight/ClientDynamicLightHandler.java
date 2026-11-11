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

import com.awesomehippo.clientdynamiclight.config.ConfigLoader;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Vector3f;

@OnlyIn(Dist.CLIENT)
public enum ClientDynamicLightHandler {
    INSTANCE;

    //TODO: tweak these or add it to config.
    private static final int MAX_UPDATES_PER_TICK = 16; // 16 should definitely avoid lag spike
    private static final int CLEANUP_TIMEOUT = 20;
    private static final int MAX_SCAN_RANGE = 64;
    private static final double MAX_DIST = 7.5D;
    private static final double MAX_DIST_SQ = MAX_DIST * MAX_DIST;
    private static final double FALLOFF_PER_BLOCK = 15.0D / MAX_DIST;
    private static final double POSITION_CHANGE_THRESHOLD = 0.25D;
    private static final double POSITION_CHANGE_THRESHOLD_SQ = POSITION_CHANGE_THRESHOLD * POSITION_CHANGE_THRESHOLD;
    private static final double STILL_THRESHOLD_SQ = 0.01D * 0.01D;
    private static final int SMALL_SOURCE_COUNT = 8;
    private static final int ENTITY_SCAN_INTERVAL = 2;
    private static final int PACKED_FULL_BLOCK = 15 * 16;
    private static final int CELL_SHIFT = 4;
    private static final double VIEW_RANGE_SECTION_MARGIN = 16.0D;
    private static final double BEHIND_CAMERA_SLACK = 1.0D;

    // may replace these maps with an unified WorldLightData class
    private final Map<Integer, DynamicLightSource> lightMap = new HashMap<>();
    private final Map<Long, List<DynamicLightSource>> lightPositions = new HashMap<>();
    private final Map<Long, Double> pendingSectionDirties = new HashMap<>();

    private volatile LightSnapshot snapshot = LightSnapshot.EMPTY;

    private Level previousWorld = null;

    public void toggle() {
        ConfigLoader.INSTANCE.getConfig().enabled = !isEnabled();
        ConfigLoader.INSTANCE.save();
    }

    // simple clean up (necessary when disabling the mod/leaving)
    private void cleanupWorldAddedLights(Level world, boolean relight) {
        if (world == null) {
            return;
        }

        snapshot = LightSnapshot.EMPTY;
        if (relight) {
            for (DynamicLightSource source : lightMap.values()) {
                relight(source, source.level);
            }
            flushRenderUpdates(world, true);
        }

        lightMap.clear();
        lightPositions.clear();
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
            }
            previousWorld = world;
        }

        if (!isEnabled()) {
            if (world != null && !lightMap.isEmpty()) {
                cleanupWorldAddedLights(world, true);
            }
            if (world != null && !pendingSectionDirties.isEmpty()) {
                flushRenderUpdates(world, true);
            }
            return;
        }

        if (world == null || player == null || mc.isPaused()) { // avoid running checks on menus
            return;
        }

        prunePendingOutsideView();
        scanEntitiesInRange(world, player);
        updateLightPositions(world);
        updateSnapshot();
        applyRenderUpdates(world, false);
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            snapshot = LightSnapshot.EMPTY;
        }
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

    private void prunePendingOutsideView() {
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
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        double rangeSq = (double) scanRange * scanRange;
        Set<Integer> seenIds = new HashSet<>();

        // player's lighting
        int playerLightLevel = EntityLightLevelHelper.getLightLevel(world, player);
        if (playerLightLevel >= 0 || lightMap.containsKey(player.getId())) {
            updateLightSource(world, player, playerLightLevel < 0 ? 0 : playerLightLevel);
            seenIds.add(player.getId());
        }

        if (fullEntityScan) {
            AABB range = player.getBoundingBox().inflate(scanRange);

            // then handle other entities
            for (Entity entity : world.getEntitiesOfClass(Entity.class, range)) {
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

                int lightLevel = EntityLightLevelHelper.getLightLevel(world, entity);
                boolean tracked = lightMap.containsKey(entity.getId());

                if (lightLevel < 0) { // skip if the helper indicates to skip
                    if (tracked) {
                        updateLightSource(world, entity, 0);
                        seenIds.add(entity.getId());
                    }
                    continue;
                }

                if (lightLevel > 0 || tracked) {
                    updateLightSource(world, entity, lightLevel);
                    seenIds.add(entity.getId());
                }
            }

            for (Map.Entry<Integer, DynamicLightSource> entry : lightMap.entrySet()) {
                if (!seenIds.contains(entry.getKey())) {
                    entry.getValue().targetLevel = 0;
                }
            }
        }

        // transfer check for sources that need to increase light level
        //TODO: this may be optimizable
        for (Integer id : seenIds) {
            DynamicLightSource source = lightMap.get(id);
            if (source == null || source.level >= source.targetLevel) {
                continue;
            }
            int maxFading = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long pos = packPosition(source.x + dx, source.y + dy, source.z + dz);
                        List<DynamicLightSource> list = lightPositions.get(pos);
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
                relight(source, source.level);
            }
        }
    }

    /* update/remove light sources, and queue updates */
    private void updateLightPositions(Level world) {
        long currentTime = world.getGameTime();
        Iterator<Map.Entry<Integer, DynamicLightSource>> it = lightMap.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<Integer, DynamicLightSource> entry = it.next();
            DynamicLightSource source = entry.getValue();
            Entity entity = world.getEntity(entry.getKey());

            // gone entity
            if (entity == null || !entity.isAlive()) {
                source.targetLevel = 0;
            }

            int oldLevel = source.level;
            boolean changed = source.tickUpdateLevel();
            if (changed) {
                relight(source, Math.max(oldLevel, source.level));
            }

            if (source.level == 0 && source.targetLevel == 0) {
                // only now the level is at 0 so we can clean up
                long pos = packPosition(source.x, source.y, source.z);
                removeSourceFromIndexes(source, pos);
                it.remove();
            } else if ((entity == null || !entity.isAlive()) && currentTime - source.lastSeen > CLEANUP_TIMEOUT) {
                // force clean up, even if level > 0, to prevent ghosts
                relight(source, source.level);
                source.targetLevel = 0;
                source.level = 0;
                long pos = packPosition(source.x, source.y, source.z);
                removeSourceFromIndexes(source, pos);
                it.remove();
            }
        }
    }

    private void relight(DynamicLightSource source, int level) {
        if (source.bakedX != source.renderX || source.bakedY != source.renderY || source.bakedZ != source.renderZ) {
            queueLightArea(source.bakedX, source.bakedY, source.bakedZ, level);
            source.bakedX = source.renderX;
            source.bakedY = source.renderY;
            source.bakedZ = source.renderZ;
        }
        queueLightArea(source.renderX, source.renderY, source.renderZ, level);
    }

    private void queueLightArea(double x, double y, double z, int level) {
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
        double radius = level / FALLOFF_PER_BLOCK;
        double radiusSq = radius * radius;

        int minSx = SectionPos.blockToSectionCoord(Mth.floor(x - radius));
        int maxSx = SectionPos.blockToSectionCoord(Mth.floor(x + radius));
        int minSy = Math.max(SectionPos.blockToSectionCoord(Mth.floor(y - radius)), player.level().getMinSection());
        int maxSy = Math.min(SectionPos.blockToSectionCoord(Mth.floor(y + radius)), player.level().getMaxSection() - 1);
        int minSz = SectionPos.blockToSectionCoord(Mth.floor(z - radius));
        int maxSz = SectionPos.blockToSectionCoord(Mth.floor(z + radius));

        for (int sx = minSx; sx <= maxSx; sx++) {
            double ox = distanceToSection(x, sx);
            for (int sy = minSy; sy <= maxSy; sy++) {
                double oy = distanceToSection(y, sy);
                for (int sz = minSz; sz <= maxSz; sz++) {
                    double oz = distanceToSection(z, sz);
                    if (ox * ox + oy * oy + oz * oz > radiusSq) {
                        continue;
                    }
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

    private static double distanceToSection(double coord, int section) {
        double min = SectionPos.sectionToBlockCoord(section);
        return coord - Mth.clamp(coord, min, min + 16.0D);
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

    private void updateSnapshot() {
        List<LightPoint> points = new ArrayList<>(lightMap.size());
        for (DynamicLightSource source : lightMap.values()) {
            if (source.level > 0) {
                points.add(new LightPoint(source.renderX, source.renderY, source.renderZ, source.level));
            }
        }

        if (points.isEmpty()) {
            snapshot = LightSnapshot.EMPTY;
            return;
        }

        Long2ObjectMap<LightPoint[]> cells = null;
        if (points.size() > SMALL_SOURCE_COUNT) {
            Map<Long, List<LightPoint>> grouped = new HashMap<>();
            for (LightPoint point : points) {
                double reach = point.level() / FALLOFF_PER_BLOCK;
                int minX = Mth.floor(point.x() - reach) >> CELL_SHIFT;
                int maxX = Mth.floor(point.x() + reach) >> CELL_SHIFT;
                int minY = Mth.floor(point.y() - reach) >> CELL_SHIFT;
                int maxY = Mth.floor(point.y() + reach) >> CELL_SHIFT;
                int minZ = Mth.floor(point.z() - reach) >> CELL_SHIFT;
                int maxZ = Mth.floor(point.z() + reach) >> CELL_SHIFT;
                for (int cx = minX; cx <= maxX; cx++) {
                    for (int cy = minY; cy <= maxY; cy++) {
                        for (int cz = minZ; cz <= maxZ; cz++) {
                            grouped.computeIfAbsent(packPosition(cx, cy, cz), k -> new ArrayList<>(2)).add(point);
                        }
                    }
                }
            }

            cells = new Long2ObjectOpenHashMap<>(grouped.size());
            for (Map.Entry<Long, List<LightPoint>> entry : grouped.entrySet()) {
                cells.put(entry.getKey().longValue(), entry.getValue().toArray(new LightPoint[0]));
            }
        }

        snapshot = new LightSnapshot(points.toArray(new LightPoint[0]), cells);
    }

    public static int applyDynamicLightToPacked(int packedLight, BlockPos pos) {
        if ((packedLight & 255) >= PACKED_FULL_BLOCK) {
            return packedLight;
        }

        return mergeDynamicLight(computeDynamicLightLevel(pos), packedLight);
    }

    public static int applyDynamicLightToPackedForEntity(Entity entity, int packedLight) {
        double light = computeDynamicLightLevel(entity.blockPosition());
        Player localPlayer = Minecraft.getInstance().player;
        if (entity == localPlayer) {
            DynamicLightSource source = INSTANCE.lightMap.get(localPlayer.getId());
            if (source != null && source.level > 0) {
                light = Math.max(light, source.level);
            }
        }

        return mergeDynamicLight(light, packedLight);
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

    private static double computeDynamicLightLevel(BlockPos pos) {
        LightSnapshot lights = INSTANCE.snapshot;
        if (lights.points().length == 0) {
            return 0.0D;
        }

        double queryX = pos.getX() + 0.5D;
        double queryY = pos.getY() + 0.5D;
        double queryZ = pos.getZ() + 0.5D;

        if (lights.cells() == null) {
            return contributeFromLights(lights.points(), queryX, queryY, queryZ);
        }

        LightPoint[] cell = lights.cells().get(packCell(pos.getX(), pos.getY(), pos.getZ()));
        if (cell == null) {
            return 0.0D;
        }

        return contributeFromLights(cell, queryX, queryY, queryZ);
    }

    private static double contributeFromLights(LightPoint[] lights, double queryX, double queryY, double queryZ) {
        double maxLight = 0.0D;
        for (LightPoint light : lights) {
            if (light.level() <= maxLight) {
                continue;
            }
            double dx = queryX - light.x();
            double dy = queryY - light.y();
            double dz = queryZ - light.z();
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > MAX_DIST_SQ) {
                continue;
            }
            double propagated = light.level() - Math.sqrt(distSq) * FALLOFF_PER_BLOCK;
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

    private static long packCell(int blockX, int blockY, int blockZ) {
        return packPosition(blockX >> CELL_SHIFT, blockY >> CELL_SHIFT, blockZ >> CELL_SHIFT);
    }

    private void addSourceToIndexes(DynamicLightSource source, long pos) {
        lightPositions.computeIfAbsent(pos, k -> new ArrayList<>(2)).add(source);
    }

    private void removeSourceFromIndexes(DynamicLightSource source, long pos) {
        List<DynamicLightSource> list = lightPositions.get(pos);
        if (list != null) {
            list.remove(source);
            if (list.isEmpty()) {
                lightPositions.remove(pos);
            }
        }
    }

    // holds the info for a light source
    private static class DynamicLightSource {
        int x, y, z;
        double renderX, renderY, renderZ;
        double bakedX, bakedY, bakedZ;
        int level;
        int targetLevel;
        long lastSeen;

        DynamicLightSource(int x, int y, int z, double renderX, double renderY, double renderZ, int level) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.renderX = renderX;
            this.renderY = renderY;
            this.renderZ = renderZ;
            this.bakedX = renderX;
            this.bakedY = renderY;
            this.bakedZ = renderZ;
            this.level = level;
            this.targetLevel = level;
            this.lastSeen = 0;
        }

        boolean hasMoved(double newRenderX, double newRenderY, double newRenderZ) {
            double dx = newRenderX - renderX;
            double dy = newRenderY - renderY;
            double dz = newRenderZ - renderZ;
            return dx * dx + dy * dy + dz * dz > STILL_THRESHOLD_SQ;
        }

        boolean hasDrifted() {
            double dx = renderX - bakedX;
            double dy = renderY - bakedY;
            double dz = renderZ - bakedZ;
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

    private record LightPoint(double x, double y, double z, int level) {
    }

    private record LightSnapshot(LightPoint[] points, Long2ObjectMap<LightPoint[]> cells) {
        static final LightSnapshot EMPTY = new LightSnapshot(new LightPoint[0], null);
    }

    /* update/create light source for an entity */
    private void updateLightSource(Level world, Entity entity, int level) {
        int bx = Mth.floor(entity.getX());
        int by = Mth.floor(entity.getY());
        int bz = Mth.floor(entity.getZ());

        double renderX = entity.getX();
        double renderY = entity.getEyeY();
        double renderZ = entity.getZ();

        DynamicLightSource source = lightMap.get(entity.getId());
        long newPos = packPosition(bx, by, bz);

        boolean isPlayer = entity == Minecraft.getInstance().player;
        if (level <= 0 && source == null && !isPlayer) { // skip if no light and no existing source
            return;
        }

        int targetted = Math.max(level, 0);

        if (source == null) {
            source = new DynamicLightSource(bx, by, bz, renderX, renderY, renderZ, 0);
            source.targetLevel = targetted;
            lightMap.put(entity.getId(), source);
            addSourceToIndexes(source, newPos);
        } else {
            long oldPos = packPosition(source.x, source.y, source.z);
            boolean movedBlock = oldPos != newPos;
            boolean moving = source.hasMoved(renderX, renderY, renderZ);
            boolean targetChanged = source.targetLevel != targetted;

            if (movedBlock) { // entity moved, update position
                removeSourceFromIndexes(source, oldPos);
                source.x = bx;
                source.y = by;
                source.z = bz;
                addSourceToIndexes(source, newPos);
            }

            source.renderX = renderX;
            source.renderY = renderY;
            source.renderZ = renderZ;

            if (targetChanged) {
                source.targetLevel = targetted;
            }

            if (movedBlock || (!moving && source.hasDrifted())) {
                relight(source, source.level);
            }
        }

        source.lastSeen = world.getGameTime();
    }

    // getter for config
    public boolean isEnabled() {
        return ConfigLoader.INSTANCE.getConfig().enabled;
    }

}
