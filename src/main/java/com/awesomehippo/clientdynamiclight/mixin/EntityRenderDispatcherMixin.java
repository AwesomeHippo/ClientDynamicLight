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

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    @Inject(
        method = "getPackedLightCoords(Lnet/minecraft/world/entity/Entity;F)I",
        at = @At("RETURN"),
        cancellable = true
    )
    private void clientdynamiclight$injectDynamicLight(Entity entity, float partialTicks, CallbackInfoReturnable<Integer> cir) {
        int modified = ClientDynamicLightHandler.applyDynamicLightToPackedForEntity(entity, cir.getReturnValue());
        if (modified != cir.getReturnValue()) {
            cir.setReturnValue(modified);
        }
    }

}