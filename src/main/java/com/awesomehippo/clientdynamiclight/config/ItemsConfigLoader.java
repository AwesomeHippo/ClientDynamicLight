package com.awesomehippo.clientdynamiclight.config;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameData;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public enum ItemsConfigLoader {
    INSTANCE;

    private static final String FILE_NAME = "config_items.json";
    private final List<ItemRule> itemRules = new ArrayList<>();

    private boolean disableInNether = false;
    private boolean disableInEnd = false;
    private boolean disableItems = false;
    private boolean disableDroppedItems = false;
    private boolean disableWieldedItems = false;

    public Integer getLightLevel(ItemStack stack, World world, boolean isDropped, boolean isWielded) {
        if (disableItems || stack == null) {
            return 0;
        }
        if ((isDropped && disableDroppedItems) || (isWielded && disableWieldedItems)) {
            return 0;
        }

        // check nether/end
        if (world != null) {
            int dimension = world.provider.dimensionId;
            if ((dimension == -1 && disableInNether) || (dimension == 1 && disableInEnd)) {
                return 0;
            }
        }

        // items rules
        for (ItemRule rule : itemRules) {
            if (rule.matches(stack)) {
                return rule.light;
            }
        }

        return 0;
    }

    /* ---------------- read/write handling for config ---------------- */
    //TODO: add more logs?

    public void loadConfig() {
        File cfg = new File(getConfigDir(), FILE_NAME);
        if (!cfg.exists()) createDefault(cfg);

        try (Reader r = new InputStreamReader(Files.newInputStream(cfg.toPath()), StandardCharsets.UTF_8)) {
            Gson gson = new Gson();
            JsonObject rootObj = gson.fromJson(r, JsonObject.class);

            disableInNether = rootObj.has("disableInNether") && rootObj.get("disableInNether").getAsBoolean();
            disableInEnd = rootObj.has("disableInEnd") && rootObj.get("disableInEnd").getAsBoolean();
            disableItems = rootObj.has("disableItems") && rootObj.get("disableItems").getAsBoolean();
            disableDroppedItems = rootObj.has("disableDroppedItems") && rootObj.get("disableDroppedItems").getAsBoolean();
            disableWieldedItems = rootObj.has("disableWieldedItems") && rootObj.get("disableWieldedItems").getAsBoolean();

            Type listType = new TypeToken<List<JsonEntry>>(){}.getType();
            List<JsonEntry> list = gson.fromJson(rootObj.getAsJsonArray("items"), listType);
            itemRules.clear();

            for (JsonEntry je : list) {
                Item item = GameData.getItemRegistry().getObject(je.id);
                if (item == null) {
                    System.err.println("[ClientDynamicLight] Unknown item/block in config: " + je.id);
                    continue;
                }
                int meta = je.meta == null ? -1 : je.meta;
                int lvl = Math.max(0, Math.min(15, je.light));
                itemRules.add(new ItemRule(item, meta, lvl));
            }
        } catch (Exception e) {
            e.printStackTrace();
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
                    System.err.println("Could not load existing items config for saving, creating new one");
                }
            }

            root.addProperty("disableInNether", disableInNether);
            root.addProperty("disableInEnd", disableInEnd);
            root.addProperty("disableItems", disableItems);
            root.addProperty("disableDroppedItems", disableDroppedItems);
            root.addProperty("disableWieldedItems", disableWieldedItems);
            if (!root.has("items")) root.add("items", new JsonArray());

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

            JsonObject root = new JsonObject();
            root.addProperty("disableInNether", false);
            root.addProperty("disableInEnd", false);
            root.addProperty("disableItems", false);
            root.addProperty("disableDroppedItems", false);
            root.addProperty("disableWieldedItems", false);

            // all the default vanilla items that should emit light
            List<JsonEntry> defaults = Arrays.asList(
                    new JsonEntry("minecraft:torch", 0, 14),
                    new JsonEntry("minecraft:lava_bucket", 0, 15),
                    new JsonEntry("minecraft:glowstone_dust", 0, 12),
                    new JsonEntry("minecraft:glowstone", 0, 12),
                    new JsonEntry("minecraft:redstone_torch", 0, 8),
                    new JsonEntry("minecraft:nether_star", 0, 12),
                    new JsonEntry("minecraft:lit_pumpkin", 0, 15),
                    new JsonEntry("minecraft:blaze_powder", 0, 12)
            );
            root.add("items", new Gson().toJsonTree(defaults, new TypeToken<List<JsonEntry>>(){}.getType()));

            try (Writer w = new OutputStreamWriter(Files.newOutputStream(f.toPath()), StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(root, w);
            }
            System.out.println("[ClientDynamicLight] Added default items config to " + f.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private File getConfigDir() {
        return new File(Loader.instance().getConfigDir(), "clientdynamiclight");
    }

    /* -------------- getters/setters -------------- */

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

    public boolean isDisableItems() {
        return disableItems;
    }

    public void setDisableItems(boolean disableItems) {
        this.disableItems = disableItems;
    }

    public boolean isDisableDroppedItems() {
        return disableDroppedItems;
    }

    public void setDisableDroppedItems(boolean disableDroppedItems) {
        this.disableDroppedItems = disableDroppedItems;
    }

    public boolean isDisableWieldedItems() {
        return disableWieldedItems;
    }

    public void setDisableWieldedItems(boolean disableWieldedItems) {
        this.disableWieldedItems = disableWieldedItems;
    }

    /* config entries class */
    private static class JsonEntry {
        String id;
        Integer meta;
        int light;

        JsonEntry(String id, Integer meta, int light) {
            this.id = id;
            this.meta = meta;
            this.light = light;
        }
    }

    // rule shouldn't be laggy on a small amount of entries
    private static class ItemRule {
        final Item item;
        final int meta;
        final int light;

        ItemRule(Item item, int meta, int light) {
            this.item = item;
            this.meta = meta;
            this.light = light;
        }

        boolean matches(ItemStack stack) {
            if (stack == null || stack.getItem() != item) return false;
            return meta == -1 || stack.getItemDamage() == meta;
        }
    }
}