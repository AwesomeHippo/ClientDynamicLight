package com.awesomehippo.clientdynamiclight;

import com.awesomehippo.clientdynamiclight.config.EntityConfigLoader;
import com.awesomehippo.clientdynamiclight.config.ItemConfigLoader;
import com.awesomehippo.clientdynamiclight.config.LightingConfigLoader;
import com.awesomehippo.clientdynamiclight.integration.BackhandUtils;

import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

public class EntityLightLevelHelper {

    /**
     * @return -1 if we should skip lighting for this entity, otherwise the light
     *         level to use for this entity.
     */
    public static int getLightLevel(World world, Entity entity) {
        {
            final boolean disableInNether = !LightingConfigLoader.INSTANCE.getConfig().enableInNether;
            final boolean disableInEnd = !LightingConfigLoader.INSTANCE.getConfig().enableInEnd;

            int dimension = world.provider.dimensionId;
            if ((dimension == -1 && disableInNether) || (dimension == 1 && disableInEnd)) {
                return 0;
            }
        }

        int blockX = MathHelper.floor_double(entity.posX);
        int blockY = MathHelper.floor_double(entity.posY);
        int blockZ = MathHelper.floor_double(entity.posZ);
        if (world.getBlock(blockX, blockY, blockZ).getMaterial() == Material.lava) return -1; // avoid graphical glitches.

        if (entity instanceof EntityItem) {
            // Items dropped on the ground.
            return ItemConfigLoader.INSTANCE.getLightLevel(
                ((EntityItem) entity).getEntityItem(),
                world,
                true, // is dropped
                false // not wielded
            );
        }

        if (entity instanceof EntityPlayer) {
            return getPlayerLightLevel((EntityPlayer) entity, world);
        }

        // Base light level for this entity type. i.e, magma cubes glow.
        return EntityConfigLoader.INSTANCE.getLightLevel(entity);
    }

    private static int getPlayerLightLevel(EntityPlayer player, World world) {
        int lightLevel = 0;

        // The item that the player is holding (or has in their offhand) may emit light,
        // so we need to check that.
        lightLevel = getHeldItemLightLevel(player, world);

        // TODO check armor.

        return lightLevel;
    }

    private static int getHeldItemLightLevel(EntityPlayer player, World world) {
        ItemStack held = player.getCurrentEquippedItem();
        ItemStack offhand = BackhandUtils.getOffhandItem(player); // returns null if Backhand isn't present or on error.

        int level = 0;
        if (held != null) {
            level = Math.max(
                level,
                ItemConfigLoader.INSTANCE.getLightLevel(
                    held,
                    world,
                    false, // not dropped
                    true // is wielded
                )
            );
        }
        if (offhand != null) {
            level = Math.max(
                level,
                ItemConfigLoader.INSTANCE.getLightLevel(
                    offhand,
                    world,
                    false, // not dropped
                    true // is wielded
                )
            );
        }
        return level;
    }

}
