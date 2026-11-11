/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.lwjgl.glfw.GLFW;

import com.awesomehippo.clientdynamiclight.ClientDynamicLightHandler;
import com.awesomehippo.clientdynamiclight.client.gui.controls.ConfigControl;
import com.awesomehippo.clientdynamiclight.client.gui.controls.ConfigSlider;
import com.awesomehippo.clientdynamiclight.client.gui.controls.ConfigToggle;
import com.awesomehippo.clientdynamiclight.config.ConfigLoader;
import com.awesomehippo.clientdynamiclight.config.EntityConfigLoader;
import com.awesomehippo.clientdynamiclight.config.ItemConfigLoader;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ClientDynamicLightConfigScreen extends Screen {
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 240;
    private static final int CONTROL_Y_SPACING = BUTTON_HEIGHT + 3;
    private static final int CONTROL_X_SPACING = 3;
    private static final int SPACER_COUNT = 4;
    private static final int ROW_COUNT = 9;

    private final Screen parent;
    private final List<ConfigControl> controls = new ArrayList<>();
    private int yOffset;
    private int contentTop;
    private int buttonHeight = BUTTON_HEIGHT;
    private int controlYSpacing = CONTROL_Y_SPACING;
    private boolean pairNextControls;
    private boolean hasCreatedLeft;

    public ClientDynamicLightConfigScreen(Screen parent) {
        super(Component.translatable("clientdynamiclight.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        controls.clear();

        pairNextControls = false;
        hasCreatedLeft = false;

        computeLayoutMetrics();
        yOffset = contentTop;

        initControls();
        loadControls();
    }

    private void computeLayoutMetrics() {
        int titleY = height / 12;
        int infoY = titleY + font.lineHeight + 6;
        contentTop = infoY + font.lineHeight + 8;

        int availableHeight = (height - 6) - contentTop;
        int defaultContentHeight = SPACER_COUNT * (BUTTON_HEIGHT / 2)
            + ROW_COUNT * CONTROL_Y_SPACING
            + BUTTON_HEIGHT;

        if (availableHeight >= defaultContentHeight) {
            buttonHeight = BUTTON_HEIGHT;
            controlYSpacing = CONTROL_Y_SPACING;
            return;
        }

        float scale = (float) availableHeight / defaultContentHeight;
        buttonHeight = Math.max(12, Math.round(BUTTON_HEIGHT * scale));
        controlYSpacing = Math.max(buttonHeight + 1, Math.round(CONTROL_Y_SPACING * scale));

        int scaledContentHeight = SPACER_COUNT * (buttonHeight / 2)
            + ROW_COUNT * controlYSpacing
            + buttonHeight;
        if (scaledContentHeight > availableHeight) {
            int overflow = scaledContentHeight - availableHeight;
            controlYSpacing = Math.max(buttonHeight + 1, controlYSpacing - (overflow + ROW_COUNT - 1) / ROW_COUNT);
        }
    }

    private void initControls() {
        spacer();

        appendToggle(
            "clientdynamiclight.entity_lights",
            "clientdynamiclight.tooltip.entity_lights",
            () -> EntityConfigLoader.INSTANCE.getConfig().enabled,
            value -> EntityConfigLoader.INSTANCE.getConfig().enabled = value
        );

        appendSlider(
            "clientdynamiclight.burning_slider",
            "clientdynamiclight.tooltip.burning_slider",
            () -> EntityConfigLoader.INSTANCE.getConfig().burningDefault,
            value -> EntityConfigLoader.INSTANCE.getConfig().burningDefault = value
        );

        pairNextControls();
        appendToggle(
            "clientdynamiclight.nether",
            "clientdynamiclight.tooltip.nether",
            () -> EntityConfigLoader.INSTANCE.getConfig().enableInNether,
            value -> EntityConfigLoader.INSTANCE.getConfig().enableInNether = value
        );
        appendToggle(
            "clientdynamiclight.end",
            "clientdynamiclight.tooltip.end",
            () -> EntityConfigLoader.INSTANCE.getConfig().enableInEnd,
            value -> EntityConfigLoader.INSTANCE.getConfig().enableInEnd = value
        );

        spacer();

        appendToggle(
            "clientdynamiclight.item_lights",
            "clientdynamiclight.tooltip.item_lights",
            () -> ItemConfigLoader.INSTANCE.getConfig().enabled,
            value -> ItemConfigLoader.INSTANCE.getConfig().enabled = value
        );

        pairNextControls();
        appendToggle(
            "clientdynamiclight.nether",
            "clientdynamiclight.tooltip.nether",
            () -> ItemConfigLoader.INSTANCE.getConfig().enableInNether,
            value -> ItemConfigLoader.INSTANCE.getConfig().enableInNether = value
        );
        appendToggle(
            "clientdynamiclight.end",
            "clientdynamiclight.tooltip.end",
            () -> ItemConfigLoader.INSTANCE.getConfig().enableInEnd,
            value -> ItemConfigLoader.INSTANCE.getConfig().enableInEnd = value
        );

        pairNextControls();
        appendToggle(
            "clientdynamiclight.dropped_item_lights",
            "clientdynamiclight.tooltip.dropped_item_lights",
            () -> ItemConfigLoader.INSTANCE.getConfig().enableDroppedItems,
            value -> ItemConfigLoader.INSTANCE.getConfig().enableDroppedItems = value
        );
        appendToggle(
            "clientdynamiclight.wielded_item_lights",
            "clientdynamiclight.tooltip.wielded_item_lights",
            () -> ItemConfigLoader.INSTANCE.getConfig().enableWieldedItems,
            value -> ItemConfigLoader.INSTANCE.getConfig().enableWieldedItems = value
        );

        appendToggle(
            "clientdynamiclight.wearing_item_lights",
            "clientdynamiclight.tooltip.wearing_item_lights",
            () -> ItemConfigLoader.INSTANCE.getConfig().enableWearingItems,
            value -> ItemConfigLoader.INSTANCE.getConfig().enableWearingItems = value
        );

        spacer();

        appendButton(
            "clientdynamiclight.reload",
            "clientdynamiclight.tooltip.reload",
            () -> {
                ConfigLoader.INSTANCE.load();
                EntityConfigLoader.INSTANCE.load();
                ItemConfigLoader.INSTANCE.load();
                loadControls();
            }
        );

        spacer();

        pairNextControls();
        appendButton("gui.done", null, () -> {
            saveControls();
            onClose();
        });
        appendButton("gui.cancel", null, this::onClose);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        int centerX = width / 2;
        String status = ClientDynamicLightHandler.INSTANCE.isEnabled()
            ? " §a(" + Component.translatable("clientdynamiclight.status.enabled").getString() + ")"
            : " §c(" + Component.translatable("clientdynamiclight.status.disabled").getString() + ")";
        String fullTitle = Component.translatable("clientdynamiclight.title").getString() + status;

        int titleY = height / 12;
        graphics.drawCenteredString(font, fullTitle, centerX, titleY, 0xFFFFFF);

        int infoY = titleY + font.lineHeight + 6;
        graphics.drawCenteredString(
            font,
            Component.translatable("clientdynamiclight.description"),
            centerX,
            infoY,
            0xCCCCCC
        );

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            saveControls();
            onClose();
            return true;
        }

        if (KeyHandler.toggleDynamicLight.matches(keyCode, scanCode)) {
            ClientDynamicLightHandler.INSTANCE.toggle();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private void pairNextControls() {
        pairNextControls = true;
        hasCreatedLeft = false;
    }

    private void spacer() {
        yOffset += buttonHeight / 2;
    }

    private void appendSlider(
        String localeKey,
        String tooltipLocaleKey,
        Supplier<Integer> loader,
        Consumer<Integer> saver
    ) {
        BoundingRect rect = computeControlPos();
        ConfigSlider control = new ConfigSlider(
            rect.x,
            rect.y,
            rect.width,
            rect.height,
            Component.translatable(localeKey),
            0,
            15,
            loader,
            saver
        );
        control.setTooltip(Tooltip.create(Component.translatable(tooltipLocaleKey)));
        controls.add(control);
        addRenderableWidget(control);
    }

    private void appendToggle(
        String localeKey,
        String tooltipLocaleKey,
        Supplier<Boolean> loader,
        Consumer<Boolean> saver
    ) {
        BoundingRect rect = computeControlPos();
        ConfigToggle control = new ConfigToggle(
            rect.x,
            rect.y,
            rect.width,
            rect.height,
            Component.translatable(localeKey),
            loader,
            saver
        );
        control.setTooltip(Tooltip.create(Component.translatable(tooltipLocaleKey)));
        controls.add(control);
        addRenderableWidget(control);
    }

    private void appendButton(String localeKey, String tooltipLocaleKey, Runnable onClick) {
        BoundingRect rect = computeControlPos();
        Tooltip tooltip = tooltipLocaleKey == null ? null : Tooltip.create(Component.translatable(tooltipLocaleKey));
        addRenderableWidget(Button.builder(Component.translatable(localeKey), button -> onClick.run()).bounds(rect.x, rect.y, rect.width, rect.height).tooltip(tooltip).build());
    }

    private BoundingRect computeControlPos() {
        int centerX = width / 2;
        int buttonWidth = Math.min(BUTTON_WIDTH, width - 40);

        if (pairNextControls) {
            int pairWidth = (buttonWidth - CONTROL_X_SPACING) / 2;
            int leftX = centerX - buttonWidth / 2;
            int rightX = leftX + pairWidth + CONTROL_X_SPACING;
            int x;

            if (hasCreatedLeft) {
                pairNextControls = false;
                x = rightX;
            } else {
                hasCreatedLeft = true;
                yOffset += controlYSpacing;
                x = leftX;
            }

            return new BoundingRect(x, yOffset, pairWidth, buttonHeight);
        }

        yOffset += controlYSpacing;
        return new BoundingRect(centerX - buttonWidth / 2, yOffset, buttonWidth, buttonHeight);
    }

    private void loadControls() {
        for (ConfigControl control : controls) {
            control.load();
        }
    }

    private void saveControls() {
        for (ConfigControl control : controls) {
            control.save();
        }

        EntityConfigLoader.INSTANCE.save();
        ItemConfigLoader.INSTANCE.save();
    }

    private static class BoundingRect {
        final int x;
        final int y;
        final int width;
        final int height;

        BoundingRect(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

}