/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.client.gui.controls;

import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class ConfigToggle extends Button implements ConfigControl {
    private final Component label;
    private final Supplier<Boolean> loader;
    private final Consumer<Boolean> saver;
    private boolean value;

    public ConfigToggle(
        int x,
        int y,
        int width,
        int height,
        Component label,
        Supplier<Boolean> loader,
        Consumer<Boolean> saver
    ) {
        super(x, y, width, height, Component.empty(), button -> ((ConfigToggle) button).toggle(), DEFAULT_NARRATION);
        this.label = label;
        this.loader = loader;
        this.saver = saver;
    }

    private void toggle() {
        this.value = !this.value;
        updateMessage();
    }

    private void updateMessage() {
        ChatFormatting color = value ? ChatFormatting.GREEN : ChatFormatting.RED;
        Component status = CommonComponents.optionStatus(value).copy().withStyle(color);
        setMessage(CommonComponents.optionNameValue(label, status));
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