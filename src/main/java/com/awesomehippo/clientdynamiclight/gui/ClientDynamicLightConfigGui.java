package com.awesomehippo.clientdynamiclight.gui;

import org.lwjgl.input.Keyboard;

import com.awesomehippo.clientdynamiclight.ClientDynamicLightHandler;
import com.awesomehippo.clientdynamiclight.config.EntityConfigLoader;
import com.awesomehippo.clientdynamiclight.config.ItemConfigLoader;
import com.awesomehippo.clientdynamiclight.config.LightingConfigLoader;
import com.awesomehippo.clientdynamiclight.gui.controls.CGuiScreen;
import com.awesomehippo.clientdynamiclight.keybinds.KeyHandler;

import net.minecraft.client.gui.GuiScreen;

public class ClientDynamicLightConfigGui extends CGuiScreen {

    public ClientDynamicLightConfigGui(GuiScreen parentScreen) {
        super(parentScreen);
    }

    @Override
    protected void initControls() {
        // SECTION: Sliders

        this.appendSlider(
            "clientdynamiclight.burning_slider", "clientdynamiclight.tooltip.burning_slider",
            () -> EntityConfigLoader.INSTANCE.getConfig().burningDefault,
            (value) -> EntityConfigLoader.INSTANCE.getConfig().burningDefault = value
        );

        // SECTION: Dimension Toggles
        this.spacer();

        this.pairNextControls();
        this.appendToggle(
            "clientdynamiclight.nether", "clientdynamiclight.tooltip.nether",
            () -> LightingConfigLoader.INSTANCE.getConfig().enableInNether,
            (value) -> LightingConfigLoader.INSTANCE.getConfig().enableInNether = value
        );
        this.appendToggle(
            "clientdynamiclight.end", "clientdynamiclight.tooltip.end",
            () -> LightingConfigLoader.INSTANCE.getConfig().enableInEnd,
            (value) -> LightingConfigLoader.INSTANCE.getConfig().enableInEnd = value
        );

        // SECTION: Light Type Toggles

        this.pairNextControls();
        this.appendToggle(
            "clientdynamiclight.entity_lights", "clientdynamiclight.tooltip.entity_lights",
            () -> EntityConfigLoader.INSTANCE.getConfig().enabled,
            (value) -> EntityConfigLoader.INSTANCE.getConfig().enabled = value
        );
        this.appendToggle(
            "clientdynamiclight.item_lights", "clientdynamiclight.tooltip.item_lights",
            () -> ItemConfigLoader.INSTANCE.getConfig().enabled,
            (value) -> ItemConfigLoader.INSTANCE.getConfig().enabled = value
        );

        this.pairNextControls();
        this.appendToggle(
            "clientdynamiclight.dropped_item_lights", "clientdynamiclight.tooltip.dropped_item_lights",
            () -> ItemConfigLoader.INSTANCE.getConfig().enableDroppedItems,
            (value) -> ItemConfigLoader.INSTANCE.getConfig().enableDroppedItems = value
        );
        this.appendToggle(
            "clientdynamiclight.wielded_item_lights", "clientdynamiclight.tooltip.wielded_item_lights",
            () -> ItemConfigLoader.INSTANCE.getConfig().enableWieldedItems,
            (value) -> ItemConfigLoader.INSTANCE.getConfig().enableWieldedItems = value
        );

        // SECTION: Reload Button
        this.spacer();

        this.appendButton(
            "clientdynamiclight.reload", "clientdynamiclight.tooltip.reload",
            () -> {
                LightingConfigLoader.INSTANCE.load();
                EntityConfigLoader.INSTANCE.load();
                ItemConfigLoader.INSTANCE.load();
                this.load();
            }
        );

        // SECTION: Save/Cancel Buttons
        this.spacer();

        this.pairNextControls();
        this.appendButton(
            "gui.done", null,
            () -> {
                this.save();
                this.closeScreen();
            }
        );
        this.appendButton(
            "gui.cancel", null,
            () -> {
                this.closeScreen();
            }
        );
    }

    @Override
    public void save() {
        super.save();
        LightingConfigLoader.INSTANCE.save();
        EntityConfigLoader.INSTANCE.save();
        ItemConfigLoader.INSTANCE.save();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.save(); // Save when pressing ESC.
            this.closeScreen();
            return;
        }

        // Allow the toggling of the mod, even with the GUI open, for convenience.
        if (keyCode == KeyHandler.toggleDynamicLight.getKeyCode()) {
            ClientDynamicLightHandler.INSTANCE.toggle();
        }

        super.keyTyped(typedChar, keyCode);
    }

}
