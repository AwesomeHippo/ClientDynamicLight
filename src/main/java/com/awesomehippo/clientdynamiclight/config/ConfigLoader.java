/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.config;

public class ConfigLoader extends AbstractConfigLoader<ConfigLoader.Config> {
    public static final ConfigLoader INSTANCE = new ConfigLoader();

    public ConfigLoader() {
        super(Config.class, "config.json");
    }

    @Override
    protected Config defaultConfig() {
        return new Config();
    }

    public static class Config {
        public boolean enabled = true;
    }

}