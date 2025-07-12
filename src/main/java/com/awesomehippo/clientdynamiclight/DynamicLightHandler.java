package com.awesomehippo.clientdynamiclight;

import com.awesomehippo.clientdynamiclight.config.EntityConfigLoader;
import com.awesomehippo.clientdynamiclight.config.NodesConfigLoader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

import java.util.*;

//TODO: may need some more optimizations
@SideOnly(Side.CLIENT)
public enum DynamicLightHandler {
    INSTANCE;

    // might tweak these
    private static final int RADIUS = 8;
    private static final int SCAN_RANGE = 22;
    private static final int SCAN_INTERVAL = 14;
    private static final int LIGHT_UPDATE_INTERVAL = 10;
    private static final BlockOffset[] SPHERE = buildOptimizedSphere(RADIUS);

    private final Map<Integer, EntityLightData> tracked = new HashMap<>();
    private final Set<Long> pendingUpdates = new HashSet<>();
    private int scanCooldown = 0;
    private int lightUpdateCooldown = 0;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.theWorld;

        //TODO: add support for all the player entities around, so we can see their light
        EntityPlayer player = mc.thePlayer;
        if (world == null || player == null) return;

        if (--scanCooldown <= 0) {
            scanCooldown = SCAN_INTERVAL;
            scanForLightEntities(world, player);
        }

        updateEntityLights(world);

        // batch updates so we're not killing the FPS
        if (--lightUpdateCooldown <= 0) {
            lightUpdateCooldown = LIGHT_UPDATE_INTERVAL;
            applyPendingLightChanges(world);
        }

        cleanUpStaticEntities(world);
    }

    private void scanForLightEntities(World world, EntityPlayer player) {
        double px = player.posX, py = player.posY, pz = player.posZ;
        double rangeSq = SCAN_RANGE * SCAN_RANGE;

        Set<Integer> seen = new HashSet<>();

        ItemStack held = player.getCurrentEquippedItem();
        int level = (held != null) ? NodesConfigLoader.INSTANCE.getLightLevel(held, world) : 0;
        updateTracked(world, player.getEntityId(), player.posX, player.posY, player.posZ, level, seen);

        for (Object o : world.loadedEntityList) {
            Entity e = (Entity) o;
            if (e == player) continue;

            double dx = e.posX - px, dy = e.posY - py, dz = e.posZ - pz;
            if (dx * dx + dy * dy + dz * dz > rangeSq) continue;

            int lightLevel = 0;
            int blockX = MathHelper.floor_double(e.posX);
            int blockY = MathHelper.floor_double(e.posY);
            int blockZ = MathHelper.floor_double(e.posZ);
            boolean isInLava = world.getBlock(blockX, blockY, blockZ).getMaterial() == Material.lava;
            boolean isInBrightArea = world.getLightBrightnessForSkyBlocks(blockX, blockY, blockZ, 0) >> 4 >= 14;

            // reduce lag by skipping useless lights... *Burning* only yet
            if (isInLava || (e.isBurning() && isInBrightArea)) {
                continue;
            }

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


            updateTracked(world, e.getEntityId(), e.posX, e.posY, e.posZ, lightLevel, seen);
        }

        tracked.entrySet().removeIf(entry -> {
            if (!seen.contains(entry.getKey())) {
                entry.getValue().clear(world);
                return true;
            }
            return false;
        });
    }

    private void updateTracked(World world, int id, double x, double y, double z, int level, Set<Integer> seen) {
        if (level <= 0) {
            EntityLightData old = tracked.remove(id);
            if (old != null) {
                old.clear(world);
            }
            return;
        }

        int bx = MathHelper.floor_double(x);
        int by = MathHelper.floor_double(y);
        int bz = MathHelper.floor_double(z);

        EntityLightData data = tracked.get(id);
        if (data == null) {
            tracked.put(id, new EntityLightData(bx, by, bz, level));
        } else if (data.x != bx || data.y != by || data.z != bz || data.level != level) {
            data.move(world, bx, by, bz, level); // we update the state since it moved or changed light
        }

        tracked.get(id).lastSeen = world.getTotalWorldTime();
        seen.add(id);
    }

    private void updateEntityLights(World world) {
        for (EntityLightData data : tracked.values()) {
            if (data.needsUpdate) {
                data.inject(world, pendingUpdates);
            }
        }
    }

    private void applyPendingLightChanges(World world) {
        if (pendingUpdates.isEmpty()) return;
        // unpack so then they're updated
        for (long packed : pendingUpdates) {
            int[] coords = unpack(packed);
            world.markBlockRangeForRenderUpdate(coords[0], coords[1], coords[2], coords[0], coords[1], coords[2]);
        }
        pendingUpdates.clear();
    }

    private void cleanUpStaticEntities(World world) {
        long now = world.getTotalWorldTime();
        tracked.entrySet().removeIf(entry -> {
            Entity e = world.getEntityByID(entry.getKey());
            boolean stale = e == null || e.isDead || now - entry.getValue().lastSeen > 40;
            if (stale) {
                entry.getValue().clear(world); // clear to refresh light
            }

            return stale;
        });
    }

    // sphere for light spread
    private static BlockOffset[] buildOptimizedSphere(int radius) {
        List<BlockOffset> result = new ArrayList<>();
        int rSq = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > rSq) continue;
                    int dist = (int) Math.round(Math.sqrt(distSq));
                    result.add(new BlockOffset(dx, dy, dz, dist));
                }
            }
        }
        result.sort(Comparator.comparingInt(o -> o.dist));

        return result.toArray(new BlockOffset[0]);
    }

    // no BlockPos available on 1.7.10.. kinda hacky
    // so here we go with a compact replacement
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }
    private static int[] unpack(long packed) {
        int x = (int) (packed >> 38);
        int y = (int) ((packed >> 26) & 0xFFF);
        int z = (int) (packed & 0x3FFFFFF);
        return new int[]{x, y, z};
    }

    // light info data for an entity (where it is and how bright)
    private static class EntityLightData {
        int x, y, z, level;
        long lastSeen = 0;
        boolean needsUpdate = true;
        final Set<Long> affected = new HashSet<>();

        EntityLightData(int x, int y, int z, int level) {
            move(null, x, y, z, level);
        }

        // update entity's state
        void move(World world, int nx, int ny, int nz, int nlevel) {
            if (world != null) clear(world);
            this.x = nx;
            this.y = ny;
            this.z = nz;
            this.level = nlevel;
            this.needsUpdate = true;
        }


        // used to create the light sphere around the entity
        void inject(World world, Set<Long> pendingUpdates) {
            affected.clear();
            for (BlockOffset offset : SPHERE) {
                int value = level - offset.dist;
                if (value <= 0) break;

                int xx = x + offset.dx;
                int yy = y + offset.dy;
                int zz = z + offset.dz;
                if (yy < 0 || yy > 255) continue;

                int current = world.getSavedLightValue(EnumSkyBlock.Block, xx, yy, zz);
                if (value <= current) continue;

                world.setLightValue(EnumSkyBlock.Block, xx, yy, zz, value);
                long packed = pack(xx, yy, zz);
                affected.add(packed);
                pendingUpdates.add(packed);
            }
            needsUpdate = false;
        }

        // then used to wipe out the light from the entity’s old position
        void clear(World world) {
            for (long packed : affected) {
                int[] coords = unpack(packed);
                world.updateLightByType(EnumSkyBlock.Block, coords[0], coords[1], coords[2]);
                world.markBlockRangeForRenderUpdate(coords[0], coords[1], coords[2], coords[0], coords[1], coords[2]);
            }
            affected.clear();
        }
    }

    private static final class BlockOffset {
        final int dx, dy, dz, dist;

        BlockOffset(int dx, int dy, int dz, int dist) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.dist = dist;
        }
    }
}
