package com.awesomehippo.clientdynamiclight.gui.controls;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.awesomehippo.clientdynamiclight.gui.controls.CGuiScreen.BoundingRect;

import cpw.mods.fml.client.config.GuiButtonExt;
import net.minecraft.util.StatCollector;

public class CGuiToggle extends GuiButtonExt implements CGuiControl {
    private final String label;
    private final String tooltip;

    private final Supplier<Boolean> loader;
    private final Consumer<Boolean> saver;

    private boolean value;

    public CGuiToggle(
        int id,
        BoundingRect rect,
        String localeKey, String tooltipLocaleKey,
        Supplier<Boolean> loader, Consumer<Boolean> saver
    ) {
        super(
            id,
            rect.x, rect.y, rect.width, rect.height,
            ""
        );
        this.label = StatCollector.translateToLocal(localeKey);
        this.tooltip = tooltipLocaleKey == null ? null : StatCollector.translateToLocal(tooltipLocaleKey);
        this.loader = loader;
        this.saver = saver;
    }

    @Override
    public int id() {
        return this.id;
    }

    @Override
    public String tooltip() {
        return this.tooltip;
    }

    public void toggle() {
        this.value = !this.value;
        this.setDisplay();
    }

    private void setDisplay() {
        String checkedString = value ? "§a✓ ON" : "§c✗ OFF";
        this.displayString = this.label + ": " + checkedString;
    }

    @Override
    public void load() {
        this.value = this.loader.get();
        this.setDisplay();
    }

    @Override
    public void save() {
        this.saver.accept(this.value);
    }

}
