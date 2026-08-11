/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.config;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.awesomehippo.clientdynamiclight.ClientDynamicLight;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class ItemConfigLoader extends AbstractConfigLoader<ItemConfigLoader.ItemConfig> {
    public static final ItemConfigLoader INSTANCE = new ItemConfigLoader();

    private final Map<Item, Integer> lightByItem = new IdentityHashMap<>();

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
        if (this.config == null || this.config.items == null) {
            return;
        }

        for (ItemRule rule : this.config.items) {
            Item item = rule.item();
            if (item != null) {
                lightByItem.put(item, rule.actualLightLevel());
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

    public int getLightLevel(ItemStack stack) {
        if (stack.isEmpty()) {
            return -1;
        }

        Integer cached = lightByItem.get(stack.getItem());
        return cached != null ? cached : -1;
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

        public boolean matches(ItemStack other) {
            Item resolved = this.item();
            return resolved != null && other.is(resolved);
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
                this.item = BuiltInRegistries.ITEM.get(new ResourceLocation(this.id));

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
