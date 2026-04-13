package com.awesomehippo.clientdynamiclight.config;

import com.awesomehippo.clientdynamiclight.config.LightingConfigLoader.LightingConfig;

public class LightingConfigLoader extends AbstractConfigLoader<LightingConfig> {
    public static final LightingConfigLoader INSTANCE = new LightingConfigLoader();

    public LightingConfigLoader() {
        super(LightingConfig.class, "config.json");
    }

    @Override
    protected LightingConfig defaultConfig() {
        return new LightingConfig();
    }

    public static class LightingConfig {
        public boolean enableInNether = true;
        public boolean enableInEnd = true;

    }

}
