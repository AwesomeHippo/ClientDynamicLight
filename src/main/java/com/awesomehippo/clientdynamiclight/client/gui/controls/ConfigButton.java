/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.client.gui.controls;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class ConfigButton extends Button implements ConfigControl {
    private final Component tooltip;
    private final Runnable onClick;

    public ConfigButton(
        int x,
        int y,
        int width,
        int height,
        Component label,
        Component tooltip,
        Runnable onClick
    ) {
        super(x, y, width, height, label, button -> ((ConfigButton) button).runAction(), DEFAULT_NARRATION);
        this.tooltip = tooltip;
        this.onClick = onClick;
    }

    private void runAction() {
        onClick.run();
    }

    @Override
    public String tooltip() {
        return tooltip == null ? null : tooltip.getString();
    }

    @Override
    public void load() {
    }

    @Override
    public void save() {
    }

}