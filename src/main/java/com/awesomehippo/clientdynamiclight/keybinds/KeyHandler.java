package com.awesomehippo.clientdynamiclight.keybinds;

import com.awesomehippo.clientdynamiclight.ClientDynamicLightHandler;
import com.awesomehippo.clientdynamiclight.gui.ClientDynamicLightConfigGui;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent.KeyInputEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

public class KeyHandler {

    public static KeyBinding openConfig = new KeyBinding("Open Config", Keyboard.KEY_L, "Client Dynamic Light");
    public static KeyBinding toggleDynamicLight = new KeyBinding("Toggle Dynamic Light", Keyboard.KEY_K, "Client Dynamic Light");

    @SubscribeEvent
    public void onKeyInput(KeyInputEvent event) {
        if (openConfig.isPressed()) {
            Minecraft.getMinecraft().displayGuiScreen(new ClientDynamicLightConfigGui(null));
        }

        if (toggleDynamicLight.isPressed()) {
            ClientDynamicLightHandler.INSTANCE.toggle();
        }
    }
}