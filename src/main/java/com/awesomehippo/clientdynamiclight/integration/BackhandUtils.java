/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.integration;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import com.awesomehippo.clientdynamiclight.ClientDynamicLight;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.ReflectionHelper.UnableToFindClassException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

public class BackhandUtils {
	/**
	 * PARTIALLY IMPLEMENTS:
	 * https://github.com/GTNewHorizons/Backhand/blob/master/src/main/java/xonin/backhand/api/core/BackhandUtils.java
	 */
    private static final String CLAZZ = "xonin.backhand.api.core.BackhandUtils";

    /**
     * https://github.com/GTNewHorizons/Backhand/blob/master/src/main/java/xonin/backhand/api/core/BackhandUtils.java#L39
     * (EntityPlayer) -> ItemStack | null
     */
    private static MethodHandle h_getOffhandItem = null;
    
    public static void _init() {
    	if (!Loader.isModLoaded("backhand")) {
    		return; // Backhand isn't installed.
    	}

        ClientDynamicLight.LOGGER.info("Loading Backhand integration...");

        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            Class<?> clazz = ReflectionHelper.getClass(BackhandUtils.class.getClassLoader(), CLAZZ);
            h_getOffhandItem = lookup.findStatic(clazz, "getOffhandItem", MethodType.methodType(ItemStack.class, EntityPlayer.class));

            ClientDynamicLight.LOGGER.info("Backhand integration loaded.");
        } catch (UnableToFindClassException e) {
        	ClientDynamicLight.LOGGER.info("Couldn't find BackhandUtils class, disabling Backhand integration...");
        } catch (NoSuchMethodException e) {
        	ClientDynamicLight.LOGGER.warn("Couldn't find getOffhandItem(EntityPlayer):ItemStack method, disabling Backhand integration...");
        } catch (Exception e) {
            ClientDynamicLight.LOGGER.error("An error occurred whilst loading Backhand integration, disabling...", e);
        }
    }

    public static ItemStack getOffhandItem(EntityPlayer player) {
        if (h_getOffhandItem == null) {
            return null;
        }
        
        try {
            return (ItemStack) h_getOffhandItem.invokeExact(player);
        } catch (Throwable t) {
            ClientDynamicLight.LOGGER.error("An error occurred whilst retrieving offhand item, disabling Backhand integration...", t);
            h_getOffhandItem = null;
            return null;
        }
    }

}
