/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight;

import com.awesomehippo.clientdynamiclight.config.EntityConfigLoader;
import com.awesomehippo.clientdynamiclight.config.ItemConfigLoader;
import com.awesomehippo.clientdynamiclight.config.ItemConfigLoader.ItemCheckType;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class EntityLightLevelHelper {

    public static int getLightLevel(Level level, Entity entity) {
        int blockX = Mth.floor(entity.getX());
        int blockY = Mth.floor(entity.getY());
        int blockZ = Mth.floor(entity.getZ());

        if (level.getFluidState(new BlockPos(blockX, blockY, blockZ)).is(FluidTags.LAVA)) {
            return -1;
        }

        if (entity instanceof ItemEntity itemEntity) {
            if (!ItemConfigLoader.INSTANCE.enabled(level, ItemCheckType.DROPPED)) {
                return -1;
            }

            return ItemConfigLoader.INSTANCE.getLightLevel(itemEntity.getItem());
        }

        if (entity instanceof Player player) {
            return getPlayerLightLevel(player, level);
        }

        if (entity instanceof LivingEntity livingEntity) {
            return getEntityLivingLightLevel(livingEntity, level);
        }

        if (EntityConfigLoader.INSTANCE.enabled(level)) {
            return EntityConfigLoader.INSTANCE.getLightLevel(entity);
        }

        return -1;
    }

    private static int getPlayerLightLevel(Player player, Level level) {
        int lightLevel = -1;

        if (ItemConfigLoader.INSTANCE.enabled(level, ItemCheckType.WIELDED)) {
            lightLevel = Math.max(
                lightLevel,
                getMaxLightLevel(
                    player.getMainHandItem(),
                    player.getOffhandItem()
                )
            );
        }

        if (ItemConfigLoader.INSTANCE.enabled(level, ItemCheckType.WEARING)) {
            lightLevel = Math.max(lightLevel, getArmorLightLevel(player));
        }

        return lightLevel;
    }

    private static int getEntityLivingLightLevel(LivingEntity entity, Level level) {
        if (!EntityConfigLoader.INSTANCE.enabled(level)) {
            return -1;
        }

        int lightLevel = EntityConfigLoader.INSTANCE.getLightLevel(entity);

        if (ItemConfigLoader.INSTANCE.enabled(level, ItemCheckType.WIELDED)) {
            lightLevel = Math.max(lightLevel, getMaxLightLevel(entity.getMainHandItem()));
        }

        if (ItemConfigLoader.INSTANCE.enabled(level, ItemCheckType.WEARING)) {
            lightLevel = Math.max(lightLevel, getArmorLightLevel(entity));
        }

        return lightLevel;
    }

    private static int getArmorLightLevel(LivingEntity entity) {
        return getMaxLightLevel(
            entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET),
            entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS),
            entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST),
            entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
        );
    }

    private static int getMaxLightLevel(ItemStack... stacks) {
        int level = -1;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }

            level = Math.max(level, ItemConfigLoader.INSTANCE.getLightLevel(stack));
        }

        return level;
    }

}