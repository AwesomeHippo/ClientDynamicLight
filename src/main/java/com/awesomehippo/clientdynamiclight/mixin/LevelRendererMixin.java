/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.awesomehippo.clientdynamiclight.ClientDynamicLightHandler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(
        method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
        at = @At("RETURN"),
        cancellable = true
    )
    private static void clientdynamiclight$injectDynamicLight(
        BlockAndTintGetter level,
        BlockState state,
        BlockPos pos,
        CallbackInfoReturnable<Integer> cir
    ) {
        if (ClientDynamicLightHandler.shouldSkipDynamicLight(level, pos, state)) {
            return;
        }

        // skip solid blocks under AO (avoid strange shadow issue around edges)
        if (Minecraft.useAmbientOcclusion() && state.isSolidRender(level, pos)) {
            return;
        }

        int original = cir.getReturnValue();
        int modified = ClientDynamicLightHandler.applyDynamicLightToPacked(original, pos);
        if (modified != original) {
            cir.setReturnValue(modified);
        }
    }

}
