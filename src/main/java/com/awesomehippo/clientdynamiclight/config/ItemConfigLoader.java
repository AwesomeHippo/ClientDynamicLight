/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.awesomehippo.clientdynamiclight.ClientDynamicLight;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

public class ItemConfigLoader extends AbstractConfigLoader<ItemConfigLoader.ItemConfig> {
    public static final ItemConfigLoader INSTANCE = new ItemConfigLoader();

    private final Map<Item, Integer> lightByItem = new IdentityHashMap<>();
    private final Set<Item> waterSensitive = new HashSet<>();

    public ItemConfigLoader() {
        super(ItemConfig.class, "config_items.json");
    }

    @Override
    public void load() {
        super.load();
        rebuildCache();
    }

    public void rebuildCache() {
        lightByItem.clear();
        waterSensitive.clear();

        if (this.config.items != null) {
            for (ItemRule rule : this.config.items) {
                if (rule == null) {
                    continue;
                }

                Item item = rule.item();
                if (item != null) {
                    lightByItem.put(item, rule.actualLightLevel());
                }
            }
        }

        if (this.config.waterSensitiveItems != null) {
            for (String id : this.config.waterSensitiveItems) {
                Item item = fromRegistry(BuiltInRegistries.ITEM, id);
                if (item == null) {
                    ClientDynamicLight.LOGGER.warn("Unknown item in config: {}", id);
                } else {
                    waterSensitive.add(item);
                }
            }
        }
    }

    public boolean enabled(Level level, ItemCheckType type) {
        if (!this.config.enabled) {
            return false;
        }

        if (type == ItemCheckType.DROPPED && !this.config.enableDroppedItems) {
            return false;
        }

        if (type == ItemCheckType.WIELDED && !this.config.enableWieldedItems) {
            return false;
        }

        if (type == ItemCheckType.WEARING && !this.config.enableWearingItems) {
            return false;
        }

        if (level.dimension() == Level.NETHER && !this.config.enableInNether) {
            return false;
        }
        if (level.dimension() == Level.END && !this.config.enableInEnd) {
            return false;
        }

        return true;
    }

    public int getLightLevel(ItemStack stack, boolean underwater) {
        if (stack.isEmpty()) {
            return -1;
        }

        Item item = stack.getItem();
        if (underwater && waterSensitive.contains(item)) {
            return -1;
        }

        return lightByItem.computeIfAbsent(item, ItemConfigLoader::getBlockLightLevel);
    }

    private static int getBlockLightLevel(Item item) {
        int light = Block.byItem(item).defaultBlockState().getLightEmission();
        return light > 0 ? light : -1;
    }

    @Override
    protected ItemConfig defaultConfig() {
        return new ItemConfig();
    }

    public static class ItemConfig {
        public boolean enabled = true;

        public boolean enableInNether = true;
        public boolean enableInEnd = true;

        public boolean enableDroppedItems = true;
        public boolean enableWieldedItems = true;
        public boolean enableWearingItems = false;

        private List<ItemRule> items = Arrays.asList(
            new ItemRule("minecraft:torch", 14),
            new ItemRule("minecraft:lava_bucket", 15),
            new ItemRule("minecraft:glowstone_dust", 12),
            new ItemRule("minecraft:glowstone", 12),
            new ItemRule("minecraft:redstone_torch", 8),
            new ItemRule("minecraft:nether_star", 12),
            new ItemRule("minecraft:jack_o_lantern", 15),
            new ItemRule("minecraft:blaze_powder", 12)
        );

        private List<String> waterSensitiveItems = Arrays.asList(
            "minecraft:torch",
            "minecraft:soul_torch",
            "minecraft:redstone_torch",
            "minecraft:campfire",
            "minecraft:soul_campfire",
            "minecraft:lava_bucket"
        );
    }

    public static class ItemRule extends LazyItemRule {
        private final int light;

        public ItemRule(String id, int light) {
            super(id);
            this.light = light;
        }

        public int actualLightLevel() {
            return Math.max(0, Math.min(this.light, 15));
        }
    }

    public static class LazyItemRule {
        private final String id;

        private transient boolean hasLookedUp = false;
        private transient Item item;

        public LazyItemRule(String id) {
            this.id = id;
        }

        protected Item item() {
            if (!this.hasLookedUp) {
                this.hasLookedUp = true;
                this.item = fromRegistry(BuiltInRegistries.ITEM, this.id);

                if (this.item == null) {
                    ClientDynamicLight.LOGGER.warn("Unknown item in config: {}", this.id);
                }
            }

            return this.item;
        }
    }

    public enum ItemCheckType {
        DROPPED,
        WIELDED,
        WEARING
    }

}
