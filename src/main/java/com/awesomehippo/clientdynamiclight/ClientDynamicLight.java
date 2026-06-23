/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight;

import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.awesomehippo.clientdynamiclight.client.ClientDynamicLightConfigScreen;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod(ClientDynamicLight.MOD_ID)
public class ClientDynamicLight {

    public static final String MOD_ID = "clientdynamiclight";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public ClientDynamicLight() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            IEventBus modBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
            modBus.addListener(this::onClientSetup);

            ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                    (client, parent) -> new ClientDynamicLightConfigScreen(parent)
                )
            );
        } else {
            LOGGER.warn("Client Dynamic Light is a client-side mod. It does nothing when installed on a dedicated server.");
        }
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            getConfigDir().toFile().mkdirs();
            com.awesomehippo.clientdynamiclight.config.ItemConfigLoader.INSTANCE.load();
            com.awesomehippo.clientdynamiclight.config.EntityConfigLoader.INSTANCE.load();
            MinecraftForge.EVENT_BUS.register(ClientDynamicLightHandler.INSTANCE);
        });
    }

    public static Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get().resolve(MOD_ID);
    }

}