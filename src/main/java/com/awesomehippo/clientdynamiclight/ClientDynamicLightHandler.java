package com.awesomehippo.clientdynamiclight;

import com.awesomehippo.clientdynamiclight.config.EntityConfigLoader;
import com.awesomehippo.clientdynamiclight.config.ItemsConfigLoader;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@SideOnly(Side.CLIENT)
public enum ClientDynamicLightHandler {
    INSTANCE;

    private static final int MAX_UPDATES_PER_TICK = 16; // can tweak this, 16 definitely avoid lag spike
    private static final int LIGHT_CHANGE_THRESHOLD = 1;
    private static final int CLEANUP_TIMEOUT = 20;
    private static final int SCAN_RANGE = Math.min(Minecraft.getMinecraft().gameSettings.renderDistanceChunks * 16, 64);

    // may replace these maps with an unified WorldLightData class
    private final ConcurrentHashMap<World, Map<Integer, DynamicLightSource>> worldLightsMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<World, Map<Long, List<DynamicLightSource>>> worldLightPositions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<World, Map<Long, Integer>> worldDynamicMaxLevels = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<UpdateEntry> pendingRenderUpdates = new PriorityBlockingQueue<>();

    private final ThreadPoolExecutor executor;

    // necessary to avoid repeated lookups
    private volatile IBlockAccess lastWorld;
    private volatile Map<Integer, DynamicLightSource> lastLightMap;
    private volatile Map<Long, List<DynamicLightSource>> lastLightPositions;
    private volatile Map<Long, Integer> lastMaxLevels;

    public boolean dynamicLightEnabled = true;

    final int cores = Runtime.getRuntime().availableProcessors();
    final int maxThreads = cores/2; // half of the cores or whatever is reasonable
    ClientDynamicLightHandler() {
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY); //min_priority or norm -1 should be good
            return t;
        };
        executor = new ThreadPoolExecutor(1, maxThreads, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000), // 1000 tasks max
                factory,
                new ThreadPoolExecutor.DiscardOldestPolicy()); // drop oldest tasks if queue is full
    }

    /* toggle the mod and clean up if disabling */
    public void toggle() {
        dynamicLightEnabled = !dynamicLightEnabled;

        Minecraft mc = Minecraft.getMinecraft();

        if (!dynamicLightEnabled && mc.theWorld != null) {
            World world = mc.theWorld;
            cleanupWorldAddedLights(world);
        }
    }

    // simple clean up (necessary when disabling the mod/leaving)
    private void cleanupWorldAddedLights(World world) {
        if (world == null) return;

        Map<Long, List<DynamicLightSource>> lightPositions = worldLightPositions.get(world);
        if (lightPositions != null) {
            for (long packed : lightPositions.keySet()) {
                final int[] c = unpackPosition(packed);
                FMLClientHandler.instance().getClient().func_152344_a(() -> {
                    world.updateLightByType(EnumSkyBlock.Block, c[0], c[1], c[2]);
                });
            }
            lightPositions.clear();
        }

        Map<Integer, DynamicLightSource> lightMap = worldLightsMap.get(world);
        if (lightMap != null) lightMap.clear();

        Map<Long, Integer> maxLevels = worldDynamicMaxLevels.get(world);
        if (maxLevels != null) maxLevels.clear();
    }

    private World previousWorld = null;
    // main part running every tick to update lights
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!dynamicLightEnabled || event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.theWorld;
        EntityPlayer player = mc.thePlayer;

        // clean up on world change/unload to avoid potential issues
        if (world != previousWorld) {
            if (previousWorld != null) {
                cleanupWorldAddedLights(previousWorld);
                pendingRenderUpdates.clear();
                executor.getQueue().clear();
            }
            previousWorld = world;
        }

        if (world == null || player == null || mc.currentScreen != null) { // avoid running checks on menus
            return;
        }

        //
        scanEntitiesInRange(world, player);
        updateLightPositions(world);
        applyRenderUpdates(world);
    }

    /* scan for entities that might emit light within range */
    private void scanEntitiesInRange(World world, EntityPlayer player) {
        AxisAlignedBB range = AxisAlignedBB.getBoundingBox(
                player.posX - SCAN_RANGE, player.posY - SCAN_RANGE, player.posZ - SCAN_RANGE,
                player.posX + SCAN_RANGE, player.posY + SCAN_RANGE, player.posZ + SCAN_RANGE
        );
        List<Entity> entityList = world.getEntitiesWithinAABB(Entity.class, range);
        executor.execute(new ScannerRunnable(world, player, entityList));
    }

    /* update/remove light sources, and queue updates */
    private void updateLightPositions(World world) {
        Map<Integer, DynamicLightSource> lightMap = worldLightsMap.get(world);
        if (lightMap == null) return;

        long currentTime = world.getTotalWorldTime();
        Iterator<Map.Entry<Integer, DynamicLightSource>> it = lightMap.entrySet().iterator();
        Map<Long, List<DynamicLightSource>> lightPositions = worldLightPositions.get(world);

        while (it.hasNext()) {
            Map.Entry<Integer, DynamicLightSource> entry = it.next();
            DynamicLightSource source = entry.getValue();
            Entity entity = world.getEntityByID(entry.getKey());

            // gone entity
            if (entity == null || entity.isDead) {
                source.targetLevel = 0;
            }

            boolean changed = source.tickUpdateLevel();
            if (changed) {
                long pos = packPosition(source.x, source.y, source.z);
                updateMaxAndQueue(world, pos, lightPositions); // queue since level changed
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
            } else if ((entity == null || entity.isDead) && currentTime - source.lastSeen > CLEANUP_TIMEOUT) {
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
    private void updateMaxAndQueue(World world, long pos, Map<Long, List<DynamicLightSource>> lightPositions) {
        List<DynamicLightSource> sources = lightPositions.get(pos);
        int newMax = 0;
        if (sources != null && !sources.isEmpty()) {
            for (DynamicLightSource s : sources) {
                newMax = Math.max(newMax, s.level);
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
        long pos = packPosition(x, y, z);
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return;
        double dx = x - player.posX, dy = y - player.posY, dz = z - player.posZ;
        double distSq = dx*dx + dy*dy + dz*dz;
        pendingRenderUpdates.add(new UpdateEntry(pos, distSq));
    }

    private void applyRenderUpdates(World world) {
        if (pendingRenderUpdates.isEmpty()) return;

        int count = 0;
        while (!pendingRenderUpdates.isEmpty() && count < MAX_UPDATES_PER_TICK) {
            UpdateEntry entry = pendingRenderUpdates.poll();
            int[] c = unpackPosition(entry.pos);
            world.updateLightByType(EnumSkyBlock.Block, c[0], c[1], c[2]);
            count++;
        }
    }

    // for the transformer
    public static int getLightValue(IBlockAccess world, Block block, int x, int y, int z) {
        int vanilla = block.getLightValue(world, x, y, z);
        if (world instanceof WorldServer || !INSTANCE.dynamicLightEnabled) {
            return vanilla;
        }

        if (!world.equals(INSTANCE.lastWorld) || INSTANCE.lastLightMap == null) {
            INSTANCE.lastWorld = world;
            INSTANCE.lastLightMap = INSTANCE.worldLightsMap.get(world);
            INSTANCE.lastLightPositions = INSTANCE.worldLightPositions.get(world);
            INSTANCE.lastMaxLevels = INSTANCE.worldDynamicMaxLevels.get(world);
        }

        Map<Long, Integer> maxLevels = INSTANCE.lastMaxLevels;
        if (maxLevels != null) {
            long pos = packPosition(x, y, z);
            Integer dyn = maxLevels.get(pos);
            if (dyn != null) {
                return Math.max(vanilla, dyn);
            }
        }

        return vanilla;
    }


    // position utils since there's no blockpos on 1.7
    // todo: we could cache some frequent positions?
    private static long packPosition(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (long) (z & 0x3FFFFFF);
    }
    //*should be correct*
    //TODO: real implementation of a BlockPos?
    private static int[] unpackPosition(long packed) {
        int x = (int) (packed >> 38);
        int y = (int) ((packed >> 26) & 0xFFF);
        int z = (int) (packed & 0x3FFFFFF);
        if ((x & 0x2000000) != 0) x |= ~0x3FFFFFF;
        if ((z & 0x2000000) != 0) z |= ~0x3FFFFFF;

        return new int[]{x, y, z};
    }

    // holds the info for a light source
    private static class DynamicLightSource { // should be fine to keep it in the same class
        int x, y, z;
        int level;
        int targetLevel;
        long lastSeen;

        DynamicLightSource(int x, int y, int z, int level) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.level = level;
            this.targetLevel = level;
            this.lastSeen = 0;
        }

        // for smoother transition
        public boolean tickUpdateLevel() {
            if (level == targetLevel) {
                return false;
            }
            if (level < targetLevel) level++;
            else level--;

            return true;
        }
    }

    /* queue entry for light updates (sorted by distance currently) - may move this and DynamicLightSource to another files */
    private static class UpdateEntry implements Comparable<UpdateEntry> {
        long pos;
        double distSq;

        UpdateEntry(long pos, double distSq) {
            this.pos = pos;
            this.distSq = distSq;
        }

        @Override
        public int compareTo(UpdateEntry o) { // closer updates first!
            return Double.compare(distSq, o.distSq);
        }
    }

    /* async task to scan for entities light sources */
    private static class ScannerRunnable implements Runnable {
        private final World world;
        private final EntityPlayer player;
        private final List<Entity> entityList;

        ScannerRunnable(World world, EntityPlayer player, List<Entity> entityList) {
            this.world = world;
            this.player = player;
            this.entityList = entityList;
        }

        @Override
        public void run() {
            double px = player.posX;
            double py = player.posY;
            double pz = player.posZ;
            double rangeSq = SCAN_RANGE * SCAN_RANGE;

            List<Entity> entities = new ArrayList<>();

            for (Entity e : entityList) {
                // filter entities within range and skip player
                if (e == player) continue;

                double ex = e.posX - px;
                double ey = e.posY - py;
                double ez = e.posZ - pz;
                if (ex * ex + ey * ey + ez * ez > rangeSq) continue;

                entities.add(e);
            }

            // run light updates
            FMLClientHandler.instance().getClient().func_152344_a(() -> {
                Map<Integer, DynamicLightSource> lightMap = INSTANCE.worldLightsMap.computeIfAbsent(world, k -> new ConcurrentHashMap<>());
                Map<Long, List<DynamicLightSource>> lightPositions = INSTANCE.worldLightPositions.computeIfAbsent(world, k -> new ConcurrentHashMap<>());

                Map<Integer, Integer> seenLightLevels = new HashMap<>();
                Map<Integer, double[]> seenPos = new HashMap<>();

                // handle player’s wielded item light first
                int pBlockX = MathHelper.floor_double(player.posX);
                int pBlockY = MathHelper.floor_double(player.posY);
                int pBlockZ = MathHelper.floor_double(player.posZ);
                boolean playerInLava = world.getBlock(pBlockX, pBlockY, pBlockZ).getMaterial() == Material.lava;
                if (!playerInLava) {
                    ItemStack held = player.getCurrentEquippedItem();
                    int level = (held != null) ? ItemsConfigLoader.INSTANCE.getLightLevel(held, world, false, true) : 0;
                    seenLightLevels.put(player.getEntityId(), level);
                    seenPos.put(player.getEntityId(), new double[]{player.posX, player.posY, player.posZ});
                }

                // then handle other entities
                for (Entity e : entities) {
                    int blockX = MathHelper.floor_double(e.posX);
                    int blockY = MathHelper.floor_double(e.posY);
                    int blockZ = MathHelper.floor_double(e.posZ);
                    if (world.getBlock(blockX, blockY, blockZ).getMaterial() == Material.lava) continue;

                    int lightLevel = 0;
                    if (e instanceof EntityItem) {
                        lightLevel = ItemsConfigLoader.INSTANCE.getLightLevel(((EntityItem) e).getEntityItem(), world, true, false);
                    } else if (e instanceof EntityPlayer) {
                        ItemStack heldItem = ((EntityPlayer) e).getCurrentEquippedItem();
                        if (heldItem != null) {
                            lightLevel = ItemsConfigLoader.INSTANCE.getLightLevel(heldItem, world, false, true);
                        }
                    } else {
                        lightLevel = EntityConfigLoader.INSTANCE.getLightLevel(e);
                    }

                    if (lightLevel > 0 || lightMap.containsKey(e.getEntityId())) {
                        seenLightLevels.put(e.getEntityId(), lightLevel);
                        seenPos.put(e.getEntityId(), new double[]{e.posX, e.posY, e.posZ});
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

                // update sources for seen entities
                for (Map.Entry<Integer, Integer> entry : seenLightLevels.entrySet()) {
                    int id = entry.getKey();
                    int level = entry.getValue();
                    double[] p = seenPos.get(id);
                    updateLightSource(world, id, p[0], p[1], p[2], level, lightMap, lightPositions);
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
                                        for (DynamicLightSource s : list) {
                                            if (s.targetLevel <= 0 && s.level > 0) {
                                                maxFading = Math.max(maxFading, s.level);
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

    /* update/create light source for an entity */
    private static void updateLightSource(World world, int entityId, double x, double y, double z, int level, Map<Integer, DynamicLightSource> lightMap, Map<Long, List<DynamicLightSource>> lightPositions) {
        int bx = MathHelper.floor_double(x);
        int by = MathHelper.floor_double(y);
        int bz = MathHelper.floor_double(z);

        DynamicLightSource source = lightMap.get(entityId);
        long newPos = packPosition(bx, by, bz);

        boolean isPlayer = Minecraft.getMinecraft().thePlayer != null && Minecraft.getMinecraft().thePlayer.getEntityId() == entityId;
        if (level <= 0 && source == null && !isPlayer) { // skip if no light and no existing source
            return;
        }

        if (source == null) {
            source = new DynamicLightSource(bx, by, bz, 0);
            source.targetLevel = level;
            lightMap.put(entityId, source);

            List<DynamicLightSource> list = lightPositions.computeIfAbsent(newPos, k -> new ArrayList<>());
            list.add(source);

            INSTANCE.updateMaxAndQueue(world, newPos, lightPositions);
        } else {
            long oldPos = packPosition(source.x, source.y, source.z);

            if (oldPos != newPos) { // entity moved, update position
                List<DynamicLightSource> oldList = lightPositions.get(oldPos);
                if (oldList != null) {
                    oldList.remove(source);
                    if (oldList.isEmpty()) lightPositions.remove(oldPos);
                }
                INSTANCE.updateMaxAndQueue(world, oldPos, lightPositions);
                source.x = bx;
                source.y = by;
                source.z = bz;
                List<DynamicLightSource> newList = lightPositions.computeIfAbsent(newPos, k -> new ArrayList<>());
                newList.add(source);
                INSTANCE.updateMaxAndQueue(world, newPos, lightPositions);
            }

            if (source.targetLevel != level) {
                source.targetLevel = level; // then update target light level
            }
        }

        source.lastSeen = world.getTotalWorldTime();
    }

    // getter for config
    public boolean isEnabled() {
        return dynamicLightEnabled;
    }

}