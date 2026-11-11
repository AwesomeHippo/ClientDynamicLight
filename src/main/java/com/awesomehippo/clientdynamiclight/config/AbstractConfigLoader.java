/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;

import com.awesomehippo.clientdynamiclight.ClientDynamicLight;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;

public abstract class AbstractConfigLoader<T> {
    static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    private final Class<T> configClass;
    private final String fileName;
    private final java.nio.file.Path file;

    protected T config;

    public AbstractConfigLoader(Class<T> configClass, String fileName) {
        this.configClass = configClass;
        this.fileName = fileName;
        this.file = ClientDynamicLight.getConfigDir().resolve(fileName);
    }

    protected abstract T defaultConfig();

    public void load() {
        if (!Files.exists(file)) {
            this.config = this.defaultConfig();
            this.save();
            return;
        }

        try (Reader r = Files.newBufferedReader(file)) {
            this.config = GSON.fromJson(r, this.configClass);
        } catch (Exception e) {
            ClientDynamicLight.LOGGER.error("Failed to load config: {}", fileName, e);
            this.config = this.defaultConfig();
        }

        if (this.config == null) {
            ClientDynamicLight.LOGGER.error("Empty config, using defaults: {}", fileName);
            this.config = this.defaultConfig();
        }
    }

    public void save() {
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(this.config, writer);
        } catch (IOException e) {
            ClientDynamicLight.LOGGER.error("Failed to save config: {}", fileName, e);
        }
    }

    public T getConfig() {
        return this.config;
    }

    static <V> V fromRegistry(Registry<V> registry, String id) {
        ResourceLocation location = id == null ? null : ResourceLocation.tryParse(id);
        if (location == null || !registry.containsKey(location)) {
            return null;
        }

        return registry.get(location);
    }

}