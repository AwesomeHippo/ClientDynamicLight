/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.lighting.LightEngine;

@Mixin(LightEngine.class)
public class LightEngineMixin {

    // we only apply at mesh/entity time
    @ModifyReturnValue(method = "getLightValue(Lnet/minecraft/core/BlockPos;)I", at = @At("RETURN"))
    private int clientdynamiclight$injectDynamicLight(int vanilla, BlockPos pos) {
        return vanilla;
    }

}
