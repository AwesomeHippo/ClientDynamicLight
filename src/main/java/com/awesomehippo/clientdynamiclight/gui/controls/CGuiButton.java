/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.gui.controls;

import com.awesomehippo.clientdynamiclight.gui.controls.CGuiScreen.BoundingRect;

import cpw.mods.fml.client.config.GuiButtonExt;
import net.minecraft.util.StatCollector;

public class CGuiButton extends GuiButtonExt implements CGuiControl {
    private final String tooltip;

    private final Runnable onClick;

    public CGuiButton(
        int id,
        BoundingRect rect,
        String localeKey, String tooltipLocaleKey,
        Runnable onClick
    ) {
        super(
            id,
            rect.x, rect.y, rect.width, rect.height,
            StatCollector.translateToLocal(localeKey)
        );
        this.tooltip = tooltipLocaleKey == null ? null : StatCollector.translateToLocal(tooltipLocaleKey);
        this.onClick = onClick;
    }

    @Override
    public int id() {
        return this.id;
    }

    @Override
    public String tooltip() {
        return this.tooltip;
    }

    public void onClick() {
        this.onClick.run();
    }

    @Override
    public void load() {}

    @Override
    public void save() {}

}
