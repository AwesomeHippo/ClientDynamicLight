/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.awesomehippo.clientdynamiclight.ClientDynamicLight;
import com.awesomehippo.clientdynamiclight.config.EntityConfigLoader.EntityConfig;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;

public class EntityConfigLoader extends AbstractConfigLoader<EntityConfig> {
    public static final EntityConfigLoader INSTANCE = new EntityConfigLoader();

    private static final Map<String, String> LEGACY_ENTITY_IDS = Map.of(
        "LavaSlime", "minecraft:magma_cube",
        "Creeper", "minecraft:creeper"
    );

    public EntityConfigLoader() {
        super(EntityConfig.class, "config_entities.json");
    }

    public boolean enabled(Level level) {
        if (!this.config.enabled) {
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

    public int getLightLevel(Entity entity) {
        for (EntityRule rule : this.config.entities) {
            if (rule.matches(entity) && entity.isAlive()) {
                return rule.actualLightLevel();
            }
        }

        if (isBurning(entity) && this.config.burningDefault > 0) {
            return this.config.burningDefault;
        }

        return -1;
    }

    @Override
    protected EntityConfig defaultConfig() {
        return new EntityConfig();
    }

    @Override
    protected boolean applyMigration(JsonObject raw) {
        boolean migrated = false;

        if (raw.get("enabled") == null && raw.get("disableEntities") != null) {
            raw.addProperty("enabled", !raw.get("disableEntities").getAsBoolean());
            raw.addProperty("enableInNether", !raw.get("disableInNether").getAsBoolean());
            raw.addProperty("enableInEnd", !raw.get("disableInEnd").getAsBoolean());
            migrated = true;
        }

        if (raw.getAsJsonArray("entities") != null) {
            raw.getAsJsonArray("entities").forEach(element -> {
                if (!element.isJsonObject()) {
                    return;
                }

                JsonObject entry = element.getAsJsonObject();
                if (!entry.has("id")) {
                    return;
                }

                String id = entry.get("id").getAsString();
                String migratedId = LEGACY_ENTITY_IDS.get(id);
                if (migratedId != null) {
                    entry.addProperty("id", migratedId);
                } else if (!id.contains(":")) {
                    entry.addProperty("id", "minecraft:" + id.toLowerCase());
                }
            });
            migrated = true;
        }

        return migrated;
    }

    private static boolean isBurning(Entity entity) {
        return entity.isAlive() && entity.isOnFire();
    }

    public static class EntityConfig {
        public boolean enabled = true;
        public boolean enableInNether = true;
        public boolean enableInEnd = true;

        public int burningDefault = 14;

        private List<EntityRule> entities = Arrays.asList(
            new EntityRule("minecraft:magma_cube", 12, null, false),
            new EntityRule("minecraft:creeper", 15, "creeper_charged", false)
        );
    }

    public static class EntityRule extends LazyEntityRule {
        private final int light;
        private final boolean burningOnly;
        private final String special;

        public EntityRule(String id, int light, String special, boolean burningOnly) {
            super(id);
            this.light = light;
            this.special = special;
            this.burningOnly = burningOnly;
        }

        public int actualLightLevel() {
            return Math.max(0, Math.min(this.light, 15));
        }

        public boolean matches(Entity entity) {
            EntityType<?> resolved = this.entityType();
            if (resolved == null || entity.getType() != resolved) {
                return false;
            }

            if (this.burningOnly && !isBurning(entity)) {
                return false;
            }

            if (entity instanceof Creeper creeper) {
                if ("creeper_charged".equals(this.special)) {
                    return creeper.isPowered();
                }
            }

            return this.special == null;
        }
    }

    public static class LazyEntityRule {
        private final String id;

        private transient boolean hasLookedUp = false;
        private transient EntityType<?> entityType;

        public LazyEntityRule(String id) {
            this.id = id.contains(":") ? id : "minecraft:" + id.toLowerCase();
        }

        protected EntityType<?> entityType() {
            if (!this.hasLookedUp) {
                this.hasLookedUp = true;
                this.entityType = BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation(this.id));

                if (this.entityType == null) {
                    ClientDynamicLight.LOGGER.warn("Unknown entity in config: {}", this.id);
                }
            }

            return this.entityType;
        }
    }

}