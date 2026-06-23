/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.config;

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

        try (
            InputStream fis = Files.newInputStream(file);
            Reader r = new InputStreamReader(fis, StandardCharsets.UTF_8)
        ) {
            this.config = GSON.fromJson(r, this.configClass);
        } catch (Exception e) {
            ClientDynamicLight.LOGGER.error("Failed to load config: {}", fileName, e);
            this.config = this.defaultConfig();
        }
    }

    public void save() {
        try (
            OutputStream fos = Files.newOutputStream(file);
            Writer writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)
        ) {
            GSON.toJson(this.config, writer);
        } catch (IOException e) {
            ClientDynamicLight.LOGGER.error("Failed to save config: {}", fileName, e);
        }
    }

    public T getConfig() {
        return this.config;
    }

}