/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.client.gui.controls;

import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class ConfigToggle extends Button implements ConfigControl {
    private final Component label;
    private final Component tooltip;
    private final Supplier<Boolean> loader;
    private final Consumer<Boolean> saver;
    private boolean value;

    public ConfigToggle(
        int x,
        int y,
        int width,
        int height,
        Component label,
        Component tooltip,
        Supplier<Boolean> loader,
        Consumer<Boolean> saver
    ) {
        super(x, y, width, height, Component.empty(), button -> ((ConfigToggle) button).toggle(), DEFAULT_NARRATION);
        this.label = label;
        this.tooltip = tooltip;
        this.loader = loader;
        this.saver = saver;
    }

    private void toggle() {
        this.value = !this.value;
        updateMessage();
    }

    private void updateMessage() {
        String checked = value
            ? "§a✓ ON"
            : "§c✗ OFF";
        setMessage(Component.literal(label.getString() + ": " + checked));
    }

    @Override
    public String tooltip() {
        return tooltip == null ? null : tooltip.getString();
    }

    @Override
    public void load() {
        this.value = loader.get();
        updateMessage();
    }

    @Override
    public void save() {
        saver.accept(value);
    }

}