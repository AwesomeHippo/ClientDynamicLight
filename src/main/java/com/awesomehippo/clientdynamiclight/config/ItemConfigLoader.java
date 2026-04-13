package com.awesomehippo.clientdynamiclight.config;

import java.util.Arrays;
import java.util.List;

import com.awesomehippo.clientdynamiclight.ClientDynamicLight;
import com.awesomehippo.clientdynamiclight.config.ItemConfigLoader.ItemConfig;
import com.google.gson.JsonObject;

import cpw.mods.fml.common.registry.GameData;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

public class ItemConfigLoader extends AbstractConfigLoader<ItemConfig> {
    public static final ItemConfigLoader INSTANCE = new ItemConfigLoader();

    public ItemConfigLoader() {
        super(ItemConfig.class, "config_items.json");
    }

    public int getLightLevel(ItemStack stack, World world, boolean isDropped, boolean isWielded) {
        if (!this.config.enabled || stack == null) {
            return 0;
        }

        if (isDropped && !this.config.enableDroppedItems) {
            return 0;
        }

        if (isWielded && !this.config.enableWieldedItems) {
            return 0;
        }

        for (ItemRule rule : this.config.items) {
            if (rule.matches(stack)) {
                return rule.light;
            }
        }

        return 0;
    }

    @Override
    protected ItemConfig defaultConfig() {
        return new ItemConfig();
    }

    @Override
    protected boolean applyMigration(JsonObject raw) {
        if (raw.get("enabled") != null) {
            return false; // Already migrated.
        }
        
        raw.addProperty("enabled", !raw.get("disableItems").getAsBoolean());
        raw.addProperty("enableDroppedItems", !raw.get("disableDroppedItems").getAsBoolean());
        raw.addProperty("enableWieldedItems", !raw.get("disableWieldedItems").getAsBoolean());
        
        return true;
    }

    public static class ItemConfig {
        public boolean enabled = true;
        public boolean enableDroppedItems = true;
        public boolean enableWieldedItems = true;

        private List<ItemRule> items = Arrays.asList(
            new ItemRule("minecraft:torch", 0, 14),
            new ItemRule("minecraft:lava_bucket", 0, 15),
            new ItemRule("minecraft:glowstone_dust", 0, 12),
            new ItemRule("minecraft:glowstone", 0, 12),
            new ItemRule("minecraft:redstone_torch", 0, 8),
            new ItemRule("minecraft:nether_star", 0, 12),
            new ItemRule("minecraft:lit_pumpkin", 0, 15),
            new ItemRule("minecraft:blaze_powder", 0, 12)
        );

    }

    public static class ItemRule extends LazyItemRule {
        private int light;
        private int meta;

        public ItemRule(String id, int meta, int light) {
            super(id);
            this.meta = meta;
            this.light = light;
        }

        public boolean matches(ItemStack other) {
            if (other == null || this.item() == null) {
                return false;
            }

            boolean metaMatches = this.meta == -1 || other.getItemDamage() == this.meta;
            return other.getItem() == this.item() && metaMatches;
        }

    }

    /**
     * Looks up the item lazily during the first call to matches and caches the
     * result.
     */
    public static class LazyItemRule {
        private String id;

        private transient boolean hasLookedUp = false;
        private transient Item item;

        public LazyItemRule(String id) {
            this.id = id;
        }

        protected Item item() {
            if (!this.hasLookedUp) {
                this.hasLookedUp = true;
                this.item = GameData.getItemRegistry().getObject(this.id);

                if (this.item == null) {
                    ClientDynamicLight.LOGGER.warn("Unknown item in config: " + this.id);
                }
            }

            return this.item;
        }

    }

}
