package com.awesomehippo.clientdynamiclight.config;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import cpw.mods.fml.common.Loader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.monster.EntityCreeper;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public enum EntityConfigLoader {
    INSTANCE;

    private static final String FILE_NAME = "config_entities.json";
    private final List<EntityRule> EntitiesRules = new ArrayList<>();
    private int burningDefault = 15;
    private boolean disableInNether = false;
    private boolean disableInEnd = false;
    private boolean disableEntities = false;

    public Integer getLightLevel(Entity e) {

        // first
        if (e != null && e.worldObj != null) {
            int dimension = e.worldObj.provider.dimensionId;
            if ((dimension == -1 && disableInNether) || (dimension == 1 && disableInEnd)) {
                return 0;
            }
        }

        if (disableEntities) return 0;

        // entities rules
        for (EntityRule r : EntitiesRules) {
            if (r.matches(e) && e.isEntityAlive()) {
                return r.light;
            }
        }
        // then the global for the burnings one
        if (e.isBurning() && burningDefault > 0 && e.isEntityAlive()) return burningDefault;

        return 0;
    }

    /* ---------------- read/write handling for config ---------------- */
    //TODO: add more logs?

    public void loadConfig() {
        File cfg = new File(getConfigDir(), FILE_NAME);
        if (!cfg.exists()) createDefault(cfg);

        try (Reader r = new InputStreamReader(Files.newInputStream(cfg.toPath()), StandardCharsets.UTF_8)) {

            Gson gson = new Gson();
            JsonElement root = gson.fromJson(r, JsonElement.class);
            JsonObject rootObj = root.getAsJsonObject();

            // support of a 'global' key
            JsonElement bd = root.getAsJsonObject().get("burningDefault");
            burningDefault = bd == null ? 0 : Math.max(0, Math.min(15, bd.getAsInt()));
            disableInNether = rootObj.has("disableInNether") && rootObj.get("disableInNether").getAsBoolean();
            disableInEnd = rootObj.has("disableInEnd") && rootObj.get("disableInEnd").getAsBoolean();
            disableEntities = rootObj.has("disableEntities") && rootObj.get("disableEntities").getAsBoolean();

            // then, all the entries list
            Type listType = new TypeToken<List<JsonEntry>>(){}.getType();
            List<JsonEntry> list = gson.fromJson(root.getAsJsonObject().getAsJsonArray("entities"), listType);

            EntitiesRules.clear();

            for (JsonEntry je : list) {
                @SuppressWarnings("unchecked")
                Class<? extends Entity> cls =
                        (Class<? extends Entity>)EntityList.stringToClassMapping.get(je.id);

                if (cls == null) {
                    System.err.println("[ClientDynamicLight] Unknown entity in config: " + je.id);
                    continue;
                }
                int lvl = Math.max(0, Math.min(15, je.light)); // cap to 15
                EntitiesRules.add(new EntityRule(cls, false, lvl, je.special));
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void saveConfig() {
        File cfg = new File(getConfigDir(), FILE_NAME);
        try {
            cfg.getParentFile().mkdirs();

            JsonObject root = new JsonObject();
            if (cfg.exists()) {
                try (Reader r = new InputStreamReader(Files.newInputStream(cfg.toPath()), StandardCharsets.UTF_8)) {
                    JsonElement existing = new Gson().fromJson(r, JsonElement.class);
                    if (existing != null && existing.isJsonObject()) {
                        root = existing.getAsJsonObject();
                    }
                } catch (Exception e) {
                    System.err.println("Could not load existing entity config for saving, creating new one");
                }
            }

            root.addProperty("burningDefault", burningDefault);
            root.addProperty("disableInNether", disableInNether);
            root.addProperty("disableInEnd", disableInEnd);
            root.addProperty("disableEntities", disableEntities);
            if (!root.has("entities")) root.add("entities", new JsonArray());

            try (Writer w = new OutputStreamWriter(Files.newOutputStream(cfg.toPath()), StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(root, w);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void createDefault(File f) {
        try {
            f.getParentFile().mkdirs();

            // root object first for the 'global' key
            JsonObject root = new JsonObject();
            root.addProperty("burningDefault", 15);
            root.addProperty("disableInNether", false);
            root.addProperty("disableInEnd", false);
            root.addProperty("disableEntities", false);

            // all the default vanilla entities that should make light
            List<JsonEntry> defaults = Arrays.asList(
                    new JsonEntry("LavaSlime", null, 12, null),
                    new JsonEntry("Creeper", null, 15, "creeper_charged") // special support:)
            );
            root.add("entities", new Gson().toJsonTree(defaults, new TypeToken<List<JsonEntry>>(){}.getType()));

            try (Writer w = new OutputStreamWriter(Files.newOutputStream(f.toPath()), StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(root, w);
            }
            System.out.println("[ClientDynamicLight] Added default entity config to " + f.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private File getConfigDir() {
        return new File(Loader.instance().getConfigDir(), "clientdynamiclight");
    }

    /* -------------- getters/setters -------------- */

    public int getBurningDefault() {
        return burningDefault;
    }

    public void setBurningDefault(int burningDefault) {
        this.burningDefault = Math.max(0, Math.min(15, burningDefault));
    }

    public boolean isDisableInNether() {
        return disableInNether;
    }

    public void setDisableInNether(boolean disableInNether) {
        this.disableInNether = disableInNether;
    }

    public boolean isDisableInEnd() {
        return disableInEnd;
    }

    public void setDisableInEnd(boolean disableInEnd) {
        this.disableInEnd = disableInEnd;
    }

    public boolean isDisableEntities() {
        return disableEntities;
    }

    public void setDisableEntities(boolean disableEntities) {
        this.disableEntities = disableEntities;
    }

    /* config entries class */
    private static class JsonEntry {
        String id;
        Boolean burning;
        int light;
        String special;

        JsonEntry(String id, Boolean burning, int light, String special) {
            this.id = id; this.burning = burning; this.light = light; this.special = special;
        }
    }

    private static class EntityRule {
        final Class<? extends Entity> cls;
        final boolean burningOnly;
        final int light;
        final String special;

        EntityRule(Class<? extends Entity> cls, boolean burningOnly, int light, String special) {
            this.cls = cls; this.burningOnly = burningOnly; this.light = light; this.special = special;
        }

        boolean matches(Entity e) {
            if (!cls.isInstance(e)) return false;
            if (burningOnly && !e.isBurning()) return false;

            if (e instanceof EntityCreeper) {
                EntityCreeper creeper = (EntityCreeper) e;

                // special
                if ("creeper_charged".equals(special)) {
                    return creeper.getPowered() || creeper.getCreeperState() == 1;
                }
            }
            return special == null;
        }

    }
}