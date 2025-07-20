package com.awesomehippo.clientdynamiclight.gui;

import com.awesomehippo.clientdynamiclight.ClientDynamicLightHandler;
import com.awesomehippo.clientdynamiclight.config.EntityConfigLoader;
import com.awesomehippo.clientdynamiclight.config.ItemsConfigLoader;
import cpw.mods.fml.client.config.GuiButtonExt;
import cpw.mods.fml.client.config.GuiSlider;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.StatCollector;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;

import static com.awesomehippo.clientdynamiclight.keybinds.KeyHandler.toggleDynamicLight;

public class ClientDynamicLightConfigGui extends GuiScreen {

    private final GuiScreen parentScreen;

    private GuiSlider burningDefaultSlider;
    private GuiButtonExt netherButton;
    private GuiButtonExt endButton;
    private GuiButtonExt disableEntitiesButton;
    private GuiButtonExt disableItemsButton;
    private GuiButtonExt disableDroppedItemsButton;
    private GuiButtonExt disableWieldedItemsButton;
    private GuiButtonExt reloadButton;

    private boolean disableInNether = false;
    private boolean disableInEnd = false;
    private boolean disableEntities = false;
    private boolean disableItems = false;
    private boolean disableDroppedItems = false;
    private boolean disableWieldedItems = false;
    private int burningDefault = 15;

    public ClientDynamicLightConfigGui(GuiScreen parentScreen) {
        this.parentScreen = parentScreen;
        loadGlobalSettings();
    }

    // useful helpers
    private int topMargin() { return height / 7; }
    private int componentSpacing() { return 23; }
    private int btnWidth() { return Math.min(200, width - 40); }
    private int btnHeight() { return 20; }
    private int pairBtnWidth() { return (btnWidth() - 10) / 2; } // for aligning both buttons

    private String getToggleText(String label, boolean enabled) {
        return label + ": " + (enabled ? "§a✓ ON" : "§c✗ OFF"); // checkmark/cross looks good
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();

        final int centerX = width / 2;
        int y = topMargin();

        // starting with a small spacing
        y += componentSpacing();
        buttonList.add(burningDefaultSlider = new GuiSlider(
                100,
                centerX - btnWidth() / 2, y,
                btnWidth(), btnHeight(),
                StatCollector.translateToLocal("clientdynamiclight.burning_slider") + " ", "", 0, 15, burningDefault, false, true));

        y += componentSpacing();
        int leftX = centerX - btnWidth() / 2;
        int rightX = leftX + pairBtnWidth() + 10;
        buttonList.add(netherButton = new GuiButtonExt(
                101, leftX, y, pairBtnWidth(), btnHeight(),
                getToggleText(StatCollector.translateToLocal("clientdynamiclight.nether"), !disableInNether)));
        buttonList.add(endButton = new GuiButtonExt(
                102, rightX, y, pairBtnWidth(), btnHeight(),
                getToggleText(StatCollector.translateToLocal("clientdynamiclight.end"), !disableInEnd)));

        y += componentSpacing();
        buttonList.add(disableEntitiesButton = new GuiButtonExt(
                103, centerX - btnWidth() / 2, y, btnWidth(), btnHeight(),
                getToggleText(StatCollector.translateToLocal("clientdynamiclight.entity_lights"), !disableEntities)));

        y += componentSpacing();
        buttonList.add(disableItemsButton = new GuiButtonExt(
                104, centerX - btnWidth() / 2, y, btnWidth(), btnHeight(),
                getToggleText(StatCollector.translateToLocal("clientdynamiclight.item_lights"), !disableItems)));

        y += componentSpacing();
        buttonList.add(disableDroppedItemsButton = new GuiButtonExt(
                106, leftX, y, pairBtnWidth(), btnHeight(),
                getToggleText(StatCollector.translateToLocal("clientdynamiclight.dropped_item_lights"), !disableDroppedItems)));
        buttonList.add(disableWieldedItemsButton = new GuiButtonExt(
                107, rightX, y, pairBtnWidth(), btnHeight(),
                getToggleText(StatCollector.translateToLocal("clientdynamiclight.wielded_item_lights"), !disableWieldedItems)));

        y += componentSpacing();
        buttonList.add(reloadButton = new GuiButtonExt(
                105, centerX - btnWidth() / 2, y, btnWidth(), btnHeight(),
                StatCollector.translateToLocal("clientdynamiclight.reload")));

        int bottomY = height - btnHeight() - 10;
        int totalButtonWidth = 170;
        int startX = (width - totalButtonWidth) / 2;

        buttonList.add(new GuiButtonExt(200, startX, bottomY, 80, btnHeight(), StatCollector.translateToLocal("gui.done")));
        buttonList.add(new GuiButtonExt(201, startX + 90, bottomY, 80, btnHeight(), StatCollector.translateToLocal("gui.cancel")));
    }

    // handle update settings
    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 100: //(gui slider)
                break;
            case 101:
                disableInNether = !disableInNether;
                netherButton.displayString = getToggleText(StatCollector.translateToLocal("clientdynamiclight.nether"), !disableInNether);
                break;
            case 102:
                disableInEnd = !disableInEnd;
                endButton.displayString = getToggleText(StatCollector.translateToLocal("clientdynamiclight.end"), !disableInEnd);
                break;
            case 103:
                disableEntities = !disableEntities;
                disableEntitiesButton.displayString = getToggleText(StatCollector.translateToLocal("clientdynamiclight.entity_lights"), !disableEntities);
                break;
            case 104:
                disableItems = !disableItems;
                disableItemsButton.displayString = getToggleText(StatCollector.translateToLocal("clientdynamiclight.item_lights"), !disableItems);
                if (disableItems) {
                    disableDroppedItems = true;
                    disableWieldedItems = true;
                    disableDroppedItemsButton.displayString = getToggleText(StatCollector.translateToLocal("clientdynamiclight.dropped_item_lights"), !disableDroppedItems);
                    disableWieldedItemsButton.displayString = getToggleText(StatCollector.translateToLocal("clientdynamiclight.wielded_item_lights"), !disableWieldedItems);
                }
                break;
            case 106:
                disableDroppedItems = !disableDroppedItems;
                disableDroppedItemsButton.displayString = getToggleText(StatCollector.translateToLocal("clientdynamiclight.dropped_item_lights"), !disableDroppedItems);
                break;
            case 107:
                disableWieldedItems = !disableWieldedItems;
                disableWieldedItemsButton.displayString = getToggleText(StatCollector.translateToLocal("clientdynamiclight.wielded_item_lights"), !disableWieldedItems);
                break;
            case 105: // reload
                EntityConfigLoader.INSTANCE.loadConfig();
                ItemsConfigLoader.INSTANCE.loadConfig();
                loadGlobalSettings();
                burningDefaultSlider.setValue(burningDefault);
                updateSliderLabel();
                netherButton.displayString = getToggleText(StatCollector.translateToLocal("clientdynamiclight.nether"), !disableInNether);
                endButton.displayString = getToggleText(StatCollector.translateToLocal("clientdynamiclight.end"), !disableInEnd);
                disableEntitiesButton.displayString = getToggleText(StatCollector.translateToLocal("clientdynamiclight.entity_lights"), !disableEntities);
                disableItemsButton.displayString = getToggleText(StatCollector.translateToLocal("clientdynamiclight.item_lights"), !disableItems);
                disableDroppedItemsButton.displayString = getToggleText(StatCollector.translateToLocal("clientdynamiclight.dropped_item_lights"), !disableDroppedItems);
                disableWieldedItemsButton.displayString = getToggleText(StatCollector.translateToLocal("clientdynamiclight.wielded_item_lights"), !disableWieldedItems);
                break;
            case 200: // save (button or escape)
                EntityConfigLoader.INSTANCE.setBurningDefault((int) burningDefaultSlider.getValue());
                EntityConfigLoader.INSTANCE.setDisableInNether(disableInNether);
                EntityConfigLoader.INSTANCE.setDisableInEnd(disableInEnd);
                EntityConfigLoader.INSTANCE.setDisableEntities(disableEntities);
                ItemsConfigLoader.INSTANCE.setDisableInNether(disableInNether);
                ItemsConfigLoader.INSTANCE.setDisableInEnd(disableInEnd);
                ItemsConfigLoader.INSTANCE.setDisableItems(disableItems);
                ItemsConfigLoader.INSTANCE.setDisableDroppedItems(disableDroppedItems);
                ItemsConfigLoader.INSTANCE.setDisableWieldedItems(disableWieldedItems);
                EntityConfigLoader.INSTANCE.saveConfig();
                ItemsConfigLoader.INSTANCE.saveConfig();
                mc.displayGuiScreen(parentScreen);
                break;
            case 201: // cancel, no saving
                mc.displayGuiScreen(parentScreen);
                break;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        final int centerX = width / 2;

        String baseTitle = StatCollector.translateToLocal("clientdynamiclight.title");
        boolean modEnabled = ClientDynamicLightHandler.INSTANCE.isEnabled();
        String status = modEnabled
                ? " §a(" + StatCollector.translateToLocal("clientdynamiclight.status.enabled") + ")"
                : " §c(" + StatCollector.translateToLocal("clientdynamiclight.status.disabled") + ")";
        String fullTitle = baseTitle + status;

        int titleY = height / 12;
        drawCenteredString(fontRendererObj, fullTitle, centerX, titleY, 0xFFFFFF);

        int infoY = titleY + fontRendererObj.FONT_HEIGHT + 6;
        drawCenteredString(fontRendererObj,
                StatCollector.translateToLocal("clientdynamiclight.description"),
                centerX, infoY, 0xCCCCCC);

        super.drawScreen(mouseX, mouseY, partialTicks);
        drawTooltip(mouseX, mouseY);
    }

    private void drawTooltip(int mouseX, int mouseY) {
        List<String> tooltip = new ArrayList<String>();
        if (isMouseOver(burningDefaultSlider, mouseX, mouseY))
            tooltip.add(StatCollector.translateToLocal("clientdynamiclight.tooltip.burning_slider"));
        else if (isMouseOver(netherButton, mouseX, mouseY))
            tooltip.add(StatCollector.translateToLocal("clientdynamiclight.tooltip.nether"));
        else if (isMouseOver(endButton, mouseX, mouseY))
            tooltip.add(StatCollector.translateToLocal("clientdynamiclight.tooltip.end"));
        else if (isMouseOver(disableEntitiesButton, mouseX, mouseY))
            tooltip.add(StatCollector.translateToLocal("clientdynamiclight.tooltip.entity_lights"));
        else if (isMouseOver(disableItemsButton, mouseX, mouseY))
            tooltip.add(StatCollector.translateToLocal("clientdynamiclight.tooltip.item_lights"));
        else if (isMouseOver(disableDroppedItemsButton, mouseX, mouseY))
            tooltip.add(StatCollector.translateToLocal("clientdynamiclight.tooltip.dropped_item_lights"));
        else if (isMouseOver(disableWieldedItemsButton, mouseX, mouseY))
            tooltip.add(StatCollector.translateToLocal("clientdynamiclight.tooltip.wielded_item_lights"));
        else if (isMouseOver(reloadButton, mouseX, mouseY))
            tooltip.add(StatCollector.translateToLocal("clientdynamiclight.tooltip.reload"));

        if (!tooltip.isEmpty()) {
            drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
        }
    }

    // useful when loading config in the gui
    private void updateSliderLabel() {
        if (burningDefaultSlider != null) {
            burningDefaultSlider.displayString = StatCollector.translateToLocal("clientdynamiclight.burning_slider") + " " + (int) burningDefaultSlider.getValue();
        }
    }

    // for tooltips
    private boolean isMouseOver(GuiButton btn, int mouseX, int mouseY) {
        if (btn == null) return false;
        return mouseX >= btn.xPosition && mouseY >= btn.yPosition && mouseX < btn.xPosition + btn.width && mouseY < btn.yPosition + btn.height;
    }

    // handle key pressed
    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            // save when escaping before closing... (unlike cancel)
            EntityConfigLoader.INSTANCE.setBurningDefault((int) burningDefaultSlider.getValue());
            EntityConfigLoader.INSTANCE.setDisableInNether(disableInNether);
            EntityConfigLoader.INSTANCE.setDisableInEnd(disableInEnd);
            EntityConfigLoader.INSTANCE.setDisableEntities(disableEntities);
            ItemsConfigLoader.INSTANCE.setDisableInNether(disableInNether);
            ItemsConfigLoader.INSTANCE.setDisableInEnd(disableInEnd);
            ItemsConfigLoader.INSTANCE.setDisableItems(disableItems);
            ItemsConfigLoader.INSTANCE.setDisableDroppedItems(disableDroppedItems);
            ItemsConfigLoader.INSTANCE.setDisableWieldedItems(disableWieldedItems);
            EntityConfigLoader.INSTANCE.saveConfig();
            ItemsConfigLoader.INSTANCE.saveConfig();

            mc.displayGuiScreen(parentScreen);
            return;
        }
        // can toggle the mod even in the gui
        if (keyCode == toggleDynamicLight.getKeyCode()) {
            ClientDynamicLightHandler.INSTANCE.toggle();
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void loadGlobalSettings() {
        burningDefault = EntityConfigLoader.INSTANCE.getBurningDefault();
        disableInNether = EntityConfigLoader.INSTANCE.isDisableInNether();
        disableInEnd = EntityConfigLoader.INSTANCE.isDisableInEnd();
        disableEntities = EntityConfigLoader.INSTANCE.isDisableEntities();
        disableItems = ItemsConfigLoader.INSTANCE.isDisableItems();
        disableDroppedItems = ItemsConfigLoader.INSTANCE.isDisableDroppedItems();
        disableWieldedItems = ItemsConfigLoader.INSTANCE.isDisableWieldedItems();
    }
}