/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.client.gui.controls;

import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

public class ConfigSlider extends AbstractSliderButton implements ConfigControl {
    private final Component prefix;
    private final Component tooltip;
    private final Supplier<Integer> loader;
    private final Consumer<Integer> saver;
    private final int min;
    private final int max;

    public ConfigSlider(
        int x,
        int y,
        int width,
        int height,
        Component prefix,
        Component tooltip,
        int min,
        int max,
        Supplier<Integer> loader,
        Consumer<Integer> saver
    ) {
        super(x, y, width, height, Component.empty(), 0.0);
        this.prefix = prefix;
        this.tooltip = tooltip;
        this.loader = loader;
        this.saver = saver;
        this.min = min;
        this.max = max;
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(prefix.copy().append(Component.literal(String.valueOf(getValueInt()))));
    }

    @Override
    protected void applyValue() {
        updateMessage();
    }

    public int getValueInt() {
        return min + (int) Math.round(value * (max - min));
    }

    public void setValueInt(int value) {
        this.value = (value - min) / (double) (max - min);
        updateMessage();
    }

    @Override
    public String tooltip() {
        return tooltip == null ? null : tooltip.getString();
    }

    @Override
    public void load() {
        setValueInt(loader.get());
    }

    @Override
    public void save() {
        saver.accept(getValueInt());
    }

}