package com.awesomehippo.clientdynamiclight;

import com.awesomehippo.clientdynamiclight.config.EntityConfigLoader;
import com.awesomehippo.clientdynamiclight.config.ItemConfigLoader;
import com.awesomehippo.clientdynamiclight.config.ItemConfigLoader.ItemCheckType;
import com.awesomehippo.clientdynamiclight.integration.BackhandUtils;

import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
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
        int blockX = MathHelper.floor_double(entity.posX);
        int blockY = MathHelper.floor_double(entity.posY);
        int blockZ = MathHelper.floor_double(entity.posZ);
        if (world.getBlock(blockX, blockY, blockZ).getMaterial() == Material.lava) return -1;

        if (entity instanceof EntityItem) {
            // Items dropped on the ground.
            if (!ItemConfigLoader.INSTANCE.enabled(world, ItemCheckType.DROPPED)) {
                return -1; // skip
            }

            return ItemConfigLoader.INSTANCE.getLightLevel(((EntityItem) entity).getEntityItem());
        }

        if (entity instanceof EntityPlayer) {
            return getPlayerLightLevel((EntityPlayer) entity, world);
        }

        if (entity instanceof EntityLivingBase) {
            return getEntityLivingLightLevel((EntityLivingBase) entity, world);
        }

        // Base light level for the non-living.
        if (EntityConfigLoader.INSTANCE.enabled(world)) {
            return EntityConfigLoader.INSTANCE.getLightLevel(entity);
        } else {
            return -1; // skip
        }
    }

    private static int getPlayerLightLevel(EntityPlayer player, World world) {
        int lightLevel = -1; // if we don't find a light level, we'll skip lighting for this player.

        // Check their wielded items.
        if (ItemConfigLoader.INSTANCE.enabled(world, ItemCheckType.WIELDED)) {
            lightLevel = Math.max(
                lightLevel,
                getMaxLightLevel(
                    world,
                    player.getCurrentEquippedItem(), // main hand, possibly null
                    BackhandUtils.getOffhandItem(player) // returns null if Backhand isn't present or on error.
                )
            );
        }

        // Check their armor.
        if (ItemConfigLoader.INSTANCE.enabled(world, ItemCheckType.WEARING)) {
            lightLevel = Math.max(
                lightLevel,
                getArmorLightLevel(player, world)
            );
        }

        return lightLevel;
    }

    private static int getEntityLivingLightLevel(EntityLivingBase entity, World world) {
        if (!EntityConfigLoader.INSTANCE.enabled(world)) {
            return -1; // skip
        }

        // Check their base light level, may result in -1.
        int lightLevel = EntityConfigLoader.INSTANCE.getLightLevel(entity);

        // Check their wielded item.
        if (ItemConfigLoader.INSTANCE.enabled(world, ItemCheckType.WIELDED)) {
            lightLevel = Math.max(
                lightLevel,
                getMaxLightLevel(
                    world,
                    entity.getEquipmentInSlot(0) // main hand
                )
            );
        }

        // Check their armor.
        if (ItemConfigLoader.INSTANCE.enabled(world, ItemCheckType.WEARING)) {
            lightLevel = Math.max(
                lightLevel,
                getArmorLightLevel(entity, world)
            );
        }

        return lightLevel;
    }

    private static int getArmorLightLevel(EntityLivingBase entity, World world) {
        return getMaxLightLevel(
            world,
            entity.getEquipmentInSlot(1), // boots
            entity.getEquipmentInSlot(2), // leggings
            entity.getEquipmentInSlot(3), // chestplate
            entity.getEquipmentInSlot(4)  // helmet
        );
    }

    private static int getMaxLightLevel(World world, ItemStack... stacks) {
        int level = 0;
        for (ItemStack stack : stacks) {
            if (stack == null) continue;

            level = Math.max(
                level,
                ItemConfigLoader.INSTANCE.getLightLevel(stack)
            );
        }
        return level;
    }

}
