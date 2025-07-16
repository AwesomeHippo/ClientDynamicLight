package com.awesomehippo.clientdynamiclight;

import com.awesomehippo.clientdynamiclight.config.EntityConfigLoader;
import com.awesomehippo.clientdynamiclight.config.NodesConfigLoader;
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
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@SideOnly(Side.CLIENT)
public enum DynamicLightHandler {
    INSTANCE;

    // SCAN_RANGE seems fine like that, but we could make it configurable
    private static final int SCAN_RANGE = Math.min(Minecraft.getMinecraft().gameSettings.renderDistanceChunks * 16, 64);
    //private static final int SCAN_INTERVAL = 4;
    //private static final int UPDATE_INTERVAL = 1;

    private final ConcurrentHashMap<World, Map<Integer, DynamicLightSource>> worldLightsMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<World, Map<Long, List<DynamicLightSource>>> worldLightPositions = new ConcurrentHashMap<>();
    private final Set<Long> pendingRenderUpdates = new HashSet<>();

    //private int scanCooldown = 0;
    //private int updateCooldown = 0;
    private IBlockAccess lastWorld;
    private Map<Integer, DynamicLightSource> lastLightMap;
    private Map<Long, List<DynamicLightSource>> lastLightPositions;

    public boolean dynamicLightEnabled = true;
    public void toggle() {
        dynamicLightEnabled = !dynamicLightEnabled;

        Minecraft mc = Minecraft.getMinecraft();

        // would flood too much
        /*
        if (mc.thePlayer != null) {
            ChatComponentText msg = new ChatComponentText("Dynamic Light: ");
            ChatComponentText status = new ChatComponentText(dynamicLightEnabled ? "Enabled" : "Disabled");
            status.setChatStyle(new ChatStyle().setColor(dynamicLightEnabled ? EnumChatFormatting.GREEN : EnumChatFormatting.RED));
            msg.appendSibling(status);

            mc.thePlayer.addChatMessage(msg);
        }
        */


        // completely clear the lights it emitted when disabled (maybe put into a function?)
        if (!dynamicLightEnabled && mc.theWorld != null) {
            World world = mc.theWorld;
            Map<Long, List<DynamicLightSource>> lightPositions = worldLightPositions.get(world);
            if (lightPositions != null) {
                for (long packed : lightPositions.keySet()) {
                    int[] c = unpackPosition(packed);
                    world.updateLightByType(EnumSkyBlock.Block, c[0], c[1], c[2]);
                }
                lightPositions.clear();
            }
            Map<Integer, DynamicLightSource> lightMap = worldLightsMap.get(world);
            if (lightMap != null) {
                lightMap.clear();
            }
        }
    }
    public boolean isEnabled() {
        return dynamicLightEnabled;
    }

    // main part running every tick
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!dynamicLightEnabled || event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.theWorld;
        EntityPlayer player = mc.thePlayer;

        if (world == null || player == null) return;

        // every tick, (checking cooldown may take too long for 1 tick)
        scanForLightEntities(world, player);
        updateLightPositions(world);
        //long start = System.nanoTime();
        applyRenderUpdates(world);
        //long end = System.nanoTime();
        //System.out.println("renderupdate benchmark: " + (end - start) / 1_000_000.0 + "ms");

    }

    /* scan all the entities (within range) that should emit light
    - an entity player holding a "bright" item is considered as a light entity
     */
    private void scanForLightEntities(World world, EntityPlayer player) {
        Map<Integer, DynamicLightSource> lightMap = worldLightsMap.computeIfAbsent(world, k -> new ConcurrentHashMap<>());
        Map<Long, List<DynamicLightSource>> lightPositions = worldLightPositions.computeIfAbsent(world, k -> new ConcurrentHashMap<>());

        double px = player.posX, py = player.posY, pz = player.posZ;
        double rangeSq = SCAN_RANGE * SCAN_RANGE;
        Set<Integer> activeEntities = new HashSet<>();

        // for entities players
        ItemStack held = player.getCurrentEquippedItem();
        int level = (held != null) ? NodesConfigLoader.INSTANCE.getLightLevel(held, world) : 0;
        updateLightSource(world, player.getEntityId(), player.posX, player.posY, player.posZ, level, lightMap, lightPositions, activeEntities);

        // iter all the loaded entities (support other players holding light source)
        for (Object o : world.loadedEntityList) {
            Entity e = (Entity) o;
            if (e == player) continue;

            double dx = e.posX - px, dy = e.posY - py, dz = e.posZ - pz;
            if (dx * dx + dy * dy + dz * dz > rangeSq) continue;

            int lightLevel = 0;
            int blockX = MathHelper.floor_double(e.posX);
            int blockY = MathHelper.floor_double(e.posY);
            int blockZ = MathHelper.floor_double(e.posZ);

            // actually skipping entities in lava since the light should be enough
            if (world.getBlock(blockX, blockY, blockZ).getMaterial() == Material.lava) continue;

            if (e instanceof EntityItem) {
                lightLevel = NodesConfigLoader.INSTANCE.getLightLevel(((EntityItem) e).getEntityItem(), world);
            } else if (e instanceof EntityPlayer) {
                ItemStack heldItem = ((EntityPlayer) e).getCurrentEquippedItem();
                if (heldItem != null) {
                    lightLevel = NodesConfigLoader.INSTANCE.getLightLevel(heldItem, world);
                }
            } else {
                lightLevel = EntityConfigLoader.INSTANCE.getLightLevel(e);
            }

            updateLightSource(world, e.getEntityId(), e.posX, e.posY, e.posZ, lightLevel, lightMap, lightPositions, activeEntities);
        }

        lightMap.entrySet().removeIf(entry -> {
            if (!activeEntities.contains(entry.getKey())) {
                DynamicLightSource source = entry.getValue();
                source.targetLevel = 0;
            }
            return false;
        });
    }

    // update or CREATE light sources for entities
    private void updateLightSource(World world, int entityId, double x, double y, double z, int level,
                                   Map<Integer, DynamicLightSource> lightMap, Map<Long, List<DynamicLightSource>> lightPositions, Set<Integer> activeEntities) {
        int bx = MathHelper.floor_double(x);
        int by = MathHelper.floor_double(y);
        int bz = MathHelper.floor_double(z);

        DynamicLightSource source = lightMap.get(entityId);
        long newPos = packPosition(bx, by, bz);

        if (level <= 0) {
            if (source != null) source.targetLevel = 0;
            return;
        }

        if (source == null) {
            source = new DynamicLightSource(bx, by, bz, level);
            lightMap.put(entityId, source);
            lightPositions.computeIfAbsent(newPos, k -> new ArrayList<>()).add(source);
            queueRenderUpdate(bx, by, bz);
        } else {
            // trying to see if the entity moved significantly ; then update its position
            long oldPos = packPosition(source.x, source.y, source.z);
            //double dx = x - (source.x + 0.5);
            //double dy = y - (source.y + 0.5);
            //double dz = z - (source.z + 0.5);
            //double distSq = dx * dx + dy * dy + dz * dz;

            if (oldPos != newPos) { //  || distSq > 0.25
                List<DynamicLightSource> oldList = lightPositions.get(oldPos);
                if (oldList != null) {
                    oldList.remove(source);
                    if (oldList.isEmpty()) lightPositions.remove(oldPos);
                }
                source.x = bx;
                source.y = by;
                source.z = bz;
                lightPositions.computeIfAbsent(newPos, k -> new ArrayList<>()).add(source);
                queueRenderUpdate(bx, by, bz);
                queueRenderUpdate((int) (oldPos >> 38), (int) ((oldPos >> 26) & 0xFFF), (int) (oldPos & 0x3FFFFFF));
            }

            if (source.targetLevel != level) {
                source.targetLevel = level;
            }
        }

        source.lastSeen = world.getTotalWorldTime();
        activeEntities.add(entityId);
    }

    // update positions, light levels..
    private void updateLightPositions(World world) {
        Map<Integer, DynamicLightSource> lightMap = worldLightsMap.get(world);
        if (lightMap == null) return;

        long currentTime = world.getTotalWorldTime();
        Iterator<Map.Entry<Integer, DynamicLightSource>> it = lightMap.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<Integer, DynamicLightSource> entry = it.next();
            DynamicLightSource source = entry.getValue();
            Entity entity = world.getEntityByID(entry.getKey());

            // entity missing?:D
            if ((entity == null || entity.isDead) && source.level <= 0 && currentTime - source.lastSeen > 60) {
                long pos = packPosition(source.x, source.y, source.z);
                Map<Long, List<DynamicLightSource>> lightPositions = worldLightPositions.get(world);
                if (lightPositions != null) {
                    List<DynamicLightSource> list = lightPositions.get(pos);
                    if (list != null) {
                        list.remove(source);
                        if (list.isEmpty()) {
                            lightPositions.remove(pos);
                        }
                    }
                }
                queueRenderUpdate(source.x, source.y, source.z);
                it.remove();
            } else {
                if (source.tickUpdateLevel()) {
                    queueRenderUpdate(source.x, source.y, source.z);
                }
            }
        }
    }


    // maybe should make sure it doesn't queue too much lights
    private void queueRenderUpdate(int x, int y, int z) {
        pendingRenderUpdates.add(packPosition(x, y, z));
    }

    //(light updates) - this is the costly one that we may look to optimize
    // would probably limit the call of this function while keeping a decent amount of updates
    private void applyRenderUpdates(World world) {
        if (pendingRenderUpdates.isEmpty()) return;

        for (long packed : pendingRenderUpdates) {
            int[] c = unpackPosition(packed);
            world.updateLightByType(EnumSkyBlock.Block, c[0], c[1], c[2]);
        }
        pendingRenderUpdates.clear();
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
        }

        Map<Long, List<DynamicLightSource>> lightPositions = INSTANCE.lastLightPositions;
        if (lightPositions != null) {
            long pos = packPosition(x, y, z);
            List<DynamicLightSource> sources = lightPositions.get(pos);
            if (sources != null && !sources.isEmpty()) {
                int maxLevel = 0;
                for (DynamicLightSource s : sources) {
                    if (s.level > maxLevel) maxLevel = s.level;
                }
                return Math.max(vanilla, maxLevel);
            }
        }

        return vanilla;
    }

    // no blockpos on 1.7
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

        public boolean tickUpdateLevel() {
            if (level == targetLevel) {
                return false;
            }
            if (level < targetLevel) level++;
            else level--;

            return true;
        }
    }
}
