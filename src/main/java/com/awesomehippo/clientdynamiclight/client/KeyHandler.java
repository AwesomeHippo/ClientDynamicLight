/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.client;

import org.lwjgl.glfw.GLFW;

import com.awesomehippo.clientdynamiclight.ClientDynamicLight;
import com.awesomehippo.clientdynamiclight.ClientDynamicLightHandler;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

@Mod.EventBusSubscriber(modid = ClientDynamicLight.MOD_ID, bus = Bus.MOD, value = Dist.CLIENT)
public class KeyHandler {

    public static final String CATEGORY = "key.categories.clientdynamiclight";

    public static KeyMapping toggleDynamicLight;
    public static KeyMapping openConfig;

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        toggleDynamicLight = new KeyMapping(
            "key.clientdynamiclight.toggle",
            GLFW.GLFW_KEY_K,
            CATEGORY
        );
        openConfig = new KeyMapping(
            "key.clientdynamiclight.config",
            GLFW.GLFW_KEY_L,
            CATEGORY
        );

        event.register(toggleDynamicLight);
        event.register(openConfig);
    }

    @Mod.EventBusSubscriber(modid = ClientDynamicLight.MOD_ID, value = Dist.CLIENT)
    public static class ClientEvents {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }

            while (toggleDynamicLight.consumeClick()) {
                ClientDynamicLightHandler.INSTANCE.toggle();
            }

            while (openConfig.consumeClick()) {
                Minecraft.getInstance().setScreen(new ClientDynamicLightConfigScreen(Minecraft.getInstance().screen));
            }
        }
    }

}