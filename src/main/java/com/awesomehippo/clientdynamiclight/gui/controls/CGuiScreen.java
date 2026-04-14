package com.awesomehippo.clientdynamiclight.gui.controls;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

public abstract class CGuiScreen extends GuiScreen {
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 240;

    private static final int CONTROL_Y_SPACING = BUTTON_HEIGHT + 3;
    private static final int CONTROL_X_SPACING = 3;

    private final GuiScreen parentScreen;

    // Arbitrary length. Just needs to be large enough to hold all controls without
    // id conflicts.
    // Using an array for easy iteration when saving/loading.
    private CGuiControl[] controls = new CGuiControl[200];

    // ONLY modify during initGui().
    private int nextControlId;
    private int yOffset;
    private boolean pairNextControls;
    private boolean hasCreatedLeft;

    public CGuiScreen(GuiScreen parentScreen) {
        this.parentScreen = parentScreen;
    }

    /* -------- State -------- */

    public void closeScreen() {
        this.mc.displayGuiScreen(this.parentScreen);
    }

    public void save() {
        for (CGuiControl ctrl : controls) {
            if (ctrl != null) {
                ctrl.save();
            }
        }
    }

    public void load() {
        for (CGuiControl ctrl : controls) {
            if (ctrl != null) {
                ctrl.load();
            }
        }
    }

    /* -------- Drawing & Handling -------- */

    @Override
    protected void actionPerformed(GuiButton button) {
        CGuiControl ctrl = controls[button.id];
        if (ctrl == null) return;

        if (ctrl instanceof CGuiSlider) {
            // No action needed.
        } else if (ctrl instanceof CGuiToggle) {
            ((CGuiToggle) ctrl).toggle();
        } else if (ctrl instanceof CGuiButton) {
            ((CGuiButton) ctrl).onClick();
        }
    }

    @Override
    public final void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCustom(mouseX, mouseY, partialTicks);
        super.drawScreen(mouseX, mouseY, partialTicks);
        this.drawTooltip(mouseX, mouseY);
    }

    protected void drawCustom(int mouseX, int mouseY, float partialTicks) {
        // Override to draw custom stuff on the screen.
    }

    private void drawTooltip(int mouseX, int mouseY) {
        List<String> tooltip = new ArrayList<String>();
        for (CGuiControl ctrl : controls) {
            if (ctrl == null || ctrl.tooltip() == null) continue;

            GuiButton mcButton = (GuiButton) ctrl;
            if (mouseX >= mcButton.xPosition &&
                mouseY >= mcButton.yPosition &&
                mouseX < mcButton.xPosition + mcButton.width &&
                mouseY < mcButton.yPosition + mcButton.height) {
                tooltip.add(ctrl.tooltip());
                break;
            }
        }

        if (!tooltip.isEmpty()) {
            drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
        }
    }

    /* -------- Init -------- */

    @Override
    public final void initGui() {
        super.initGui();
        this.buttonList.clear();

        for (int i = 0; i < controls.length; i++) {
            this.controls[i] = null;
        }

        this.nextControlId = 0;
        this.yOffset = this.height / 7; // Add margin for our title and description.

        this.initControls();
        this.load();
    }

    protected abstract void initControls();

    public void pairNextControls() {
        this.pairNextControls = true;
        this.hasCreatedLeft = false;
    }

    public void spacer() {
        this.yOffset += BUTTON_HEIGHT / 2;
    }

    @SuppressWarnings("unchecked")
    public void appendSlider(String localeKey, String tooltipLocaleKey, Supplier<Integer> loader, Consumer<Integer> saver) {
        final int id = nextControlId++;
        final BoundingRect rect = computeControlPos();

        CGuiSlider ctrl = new CGuiSlider(
            id, rect,
            localeKey, tooltipLocaleKey,
            0, 15,
            true,
            loader, saver
        );

        this.buttonList.add(ctrl);
        this.controls[id] = ctrl;
    }

    @SuppressWarnings("unchecked")
    public void appendToggle(String localeKey, String tooltipLocaleKey, Supplier<Boolean> loader, Consumer<Boolean> saver) {
        final int id = nextControlId++;
        final BoundingRect rect = computeControlPos();

        CGuiToggle ctrl = new CGuiToggle(
            id, rect,
            localeKey, tooltipLocaleKey,
            loader, saver
        );

        this.buttonList.add(ctrl);
        this.controls[id] = ctrl;
    }

    @SuppressWarnings("unchecked")
    public void appendButton(String localeKey, String tooltipLocaleKey, Runnable onClick) {
        final int id = nextControlId++;
        final BoundingRect rect = computeControlPos();

        CGuiButton ctrl = new CGuiButton(
            id, rect,
            localeKey, tooltipLocaleKey,
            onClick
        );

        this.buttonList.add(ctrl);
        this.controls[id] = ctrl;
    }

    /* -------- Helpers -------- */

    private BoundingRect computeControlPos() {
        int centerX = this.width / 2;

        if (this.pairNextControls) {
            final int pairWidth = (BUTTON_WIDTH - CONTROL_X_SPACING) / 2;

            int leftX = centerX - BUTTON_WIDTH / 2;
            int rightX = leftX + pairWidth + CONTROL_X_SPACING;

            int x;

            if (this.hasCreatedLeft) {
                this.pairNextControls = false;
                x = rightX;
            } else {
                this.hasCreatedLeft = true;
                this.yOffset += CONTROL_Y_SPACING;
                x = leftX;
            }

            return new BoundingRect(
                x, this.yOffset,
                pairWidth, BUTTON_HEIGHT
            );
        } else {
            this.yOffset += CONTROL_Y_SPACING;

            return new BoundingRect(
                centerX - BUTTON_WIDTH / 2,
                this.yOffset,
                Math.min(BUTTON_WIDTH, this.width - 40),
                BUTTON_HEIGHT
            );
        }
    }

    public static class BoundingRect {
        public final int x;
        public final int y;
        public final int width;
        public final int height;

        public BoundingRect(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

}
