package com.awesomehippo.clientdynamiclight.config;

import java.util.Arrays;
import java.util.List;

import com.awesomehippo.clientdynamiclight.ClientDynamicLight;
import com.awesomehippo.clientdynamiclight.config.EntityConfigLoader.EntityConfig;
import com.google.gson.JsonObject;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.world.World;

public class EntityConfigLoader extends AbstractConfigLoader<EntityConfig> {
    public static final EntityConfigLoader INSTANCE = new EntityConfigLoader();

    public EntityConfigLoader() {
        super(EntityConfig.class, "config_entities.json");
    }

    public boolean enabled(World world) {
        if (!this.config.enabled) {
            return false;
        }

        final boolean disableInNether = !this.config.enableInNether;
        final boolean disableInEnd = !this.config.enableInEnd;

        int dimension = world.provider.dimensionId;
        if ((dimension == -1 && disableInNether) || (dimension == 1 && disableInEnd)) {
            return false;
        }

        return true;
    }

    public int getLightLevel(Entity e) {
        for (EntityRule r : this.config.entities) {
            if (r.matches(e) && e.isEntityAlive()) {
                return r.actualLightLevel();
            }
        }

        // burning default
        if (e.isBurning() && this.config.burningDefault > 0 && e.isEntityAlive()) {
            return this.config.burningDefault;
        }

        return -1; // No match, skip.
    }

    @Override
    protected EntityConfig defaultConfig() {
        return new EntityConfig();
    }

    @Override
    protected boolean applyMigration(JsonObject raw) {
        if (raw.get("enabled") != null) {
            return false; // Already migrated.
        }

        raw.addProperty("enabled", !raw.get("disableEntities").getAsBoolean());
        raw.addProperty("enableInNether", !raw.get("disableInNether").getAsBoolean());
        raw.addProperty("enableInEnd", !raw.get("disableInEnd").getAsBoolean());

        return true;
    }

    public static class EntityConfig {
        public boolean enabled = true;
        public boolean enableInNether = true;
        public boolean enableInEnd = true;

        public int burningDefault = 15;

        private List<EntityRule> entities = Arrays.asList(
            new EntityRule("LavaSlime", 12, null, false),
            new EntityRule("Creeper", 15, "creeper_charged", false)
        );

    }

    public static class EntityRule extends LazyEnitityRule {
        private int light;

        private boolean burningOnly;
        private String special;

        public EntityRule(String id, int light, String special, boolean burningOnly) {
            super(id);
            this.light = light;
            this.special = special;
            this.burningOnly = burningOnly;
        }

        public int actualLightLevel() {
            return Math.max(0, Math.min(this.light, 15));
        }

        public boolean matches(Entity e) {
            if (!this.clazz().isInstance(e)) {
                return false;
            }

            if (this.burningOnly && !e.isBurning()) {
                return false;
            }

            if (e instanceof EntityCreeper) {
                EntityCreeper creeper = (EntityCreeper) e;

                // special
                if ("creeper_charged".equals(this.special)) {
                    return creeper.getPowered() || creeper.getCreeperState() == 1;
                }
            }

            return this.special == null;
        }

    }

    /**
     * Looks up the entity lazily during the first call to matches and caches the
     * result.
     */
    public static class LazyEnitityRule {
        private String id;

        private transient boolean hasLookedUp = false;
        private transient Class<? extends Entity> entityClass = null;

        public LazyEnitityRule(String id) {
            this.id = id;
        }

        @SuppressWarnings("unchecked")
        protected Class<? extends Entity> clazz() {
            if (!this.hasLookedUp) {
                this.hasLookedUp = true;
                this.entityClass = (Class<? extends Entity>) EntityList.stringToClassMapping.get(this.id);

                if (this.entityClass == null) {
                    ClientDynamicLight.LOGGER.warn("Unknown item in config: " + this.id);
                }
            }

            return this.entityClass;
        }

    }

}
