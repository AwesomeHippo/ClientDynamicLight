/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.awesomehippo.clientdynamiclight.ClientDynamicLight;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public abstract class AbstractConfigLoader<T> {
    static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    private final Class<T> configClass;
    private final String fileName;
    private final File file;

    protected T config;

    public AbstractConfigLoader(Class<T> configClass, String fileName) {
        this.configClass = configClass;
        this.fileName = fileName;
        this.file = new File(ClientDynamicLight.getConfigDir(), fileName);
    }

    protected abstract T defaultConfig();

    /**
     * Override as needed.
     * 
     * @return true, if a migration was applied.
     */
    protected boolean applyMigration(JsonObject raw) {
        return false;
    }

    public void load() {
        if (!this.file.exists()) {
            this.config = this.defaultConfig();
            this.save(); // Write default config.
            return;
        }

        try (
            InputStream fis = Files.newInputStream(this.file.toPath());
            Reader r = new InputStreamReader(fis, StandardCharsets.UTF_8)) {

            JsonObject raw = GSON.fromJson(r, JsonObject.class);
            if (this.applyMigration(raw)) {
                ClientDynamicLight.LOGGER.info("Applied migration to config: " + this.fileName);
            }

            this.config = GSON.fromJson(raw, this.configClass);

            // Save again. If we applied a migration, this will write the migrated config.
            // If we added a new default field, this will add it to the config file.
            this.save();
        } catch (Exception e) {
            ClientDynamicLight.LOGGER.error("Failed to load config: " + this.fileName, e);
            this.config = this.defaultConfig();
        }
    }

    public void save() {
        try (
            OutputStream fos = Files.newOutputStream(this.file.toPath());
            Writer writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);) {

            GSON.toJson(this.config, writer);
        } catch (IOException e) {
            ClientDynamicLight.LOGGER.error("[ClientDynamicLight] Failed to save config: " + this.fileName, e);
        }
    }

    public T getConfig() {
        return this.config;
    }

}
