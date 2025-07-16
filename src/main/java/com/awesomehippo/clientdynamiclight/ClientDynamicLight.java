package com.awesomehippo.clientdynamiclight;

import com.awesomehippo.clientdynamiclight.config.EntityConfigLoader;
import com.awesomehippo.clientdynamiclight.config.ItemsConfigLoader;
import com.awesomehippo.clientdynamiclight.keybinds.KeyHandler;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.common.MinecraftForge;

@Mod(modid=ClientDynamicLight.MODID, name="Client Dynamic Light", version="1.1", acceptedMinecraftVersions="[1.7.10]", guiFactory = "com.awesomehippo.clientdynamiclight.gui.ConfigGuiFactory")
public class ClientDynamicLight {

    public static final String MODID = "clientdynamiclight";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        // load config files (still separated)
        ItemsConfigLoader.INSTANCE.loadConfig();
        EntityConfigLoader.INSTANCE.loadConfig();
    }


    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        FMLCommonHandler.instance().bus().register(DynamicLightHandler.INSTANCE);
        MinecraftForge.EVENT_BUS.register(DynamicLightHandler.INSTANCE);

        //should be at client anyway
        if (FMLCommonHandler.instance().getSide().isClient()) {
            ClientRegistry.registerKeyBinding(KeyHandler.openConfig);
            ClientRegistry.registerKeyBinding(KeyHandler.toggleDynamicLight);
            FMLCommonHandler.instance().bus().register(new KeyHandler());
        }
    }
}