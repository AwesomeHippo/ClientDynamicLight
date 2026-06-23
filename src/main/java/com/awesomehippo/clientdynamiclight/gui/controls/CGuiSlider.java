package com.awesomehippo.clientdynamiclight.gui.controls;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.awesomehippo.clientdynamiclight.gui.controls.CGuiScreen.BoundingRect;

import cpw.mods.fml.client.config.GuiSlider;
import net.minecraft.util.StatCollector;

public class CGuiSlider extends GuiSlider implements CGuiControl {
    private final String tooltip;

    private final Supplier<Integer> loader;
    private final Consumer<Integer> saver;

    public CGuiSlider(
        int id,
        BoundingRect rect,
        String localeKey, String tooltipLocaleKey,
        int min, int max,
        boolean drawStr,
        Supplier<Integer> loader, Consumer<Integer> saver
    ) {
        super(
            id,
            rect.x, rect.y, rect.width, rect.height,
            StatCollector.translateToLocal(localeKey) + " ", "",
            min, max, 0,
            false, drawStr
        );
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

    @Override
    public void load() {
        int value = this.loader.get();

        this.setValue(value);
        this.updateSlider(); // We have to call this to update the display string after setting the value.
    }

    @Override
    public void save() {
        int value = this.getValueInt();
        this.saver.accept(value);
    }

}
