package com.awesomehippo.clientdynamiclight;

import java.io.File;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.awesomehippo.clientdynamiclight.config.EntityConfigLoader;
import com.awesomehippo.clientdynamiclight.config.ItemConfigLoader;
import com.awesomehippo.clientdynamiclight.config.LightingConfigLoader;
import com.awesomehippo.clientdynamiclight.integration.BackhandUtils;
import com.awesomehippo.clientdynamiclight.keybinds.KeyHandler;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.common.MinecraftForge;

@Mod(modid=ClientDynamicLight.MODID, name="Client Dynamic Light", version="2.0", acceptedMinecraftVersions="[1.7.10]", guiFactory = "com.awesomehippo.clientdynamiclight.gui.ConfigGuiFactory")
public class ClientDynamicLight {

    public static final String MODID = "clientdynamiclight";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        if (!FMLCommonHandler.instance().getSide().isClient()) {
            LOGGER.warn("ClientDynamicLight is a client-side mod. This mod does nothing when installed on the server.");
            return;
        }
        
        getConfigDir().mkdirs(); // ensure config dir exists
        
    	BackhandUtils._init();

        // load config files (still separated)
    	LightingConfigLoader.INSTANCE.load();
    	ItemConfigLoader.INSTANCE.load();
        EntityConfigLoader.INSTANCE.load();
    }


    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        if (!FMLCommonHandler.instance().getSide().isClient()) {
            return; // Don't register anything if we're on the server, just in case
        }
        
        FMLCommonHandler.instance().bus().register(ClientDynamicLightHandler.INSTANCE);
        MinecraftForge.EVENT_BUS.register(ClientDynamicLightHandler.INSTANCE);

        ClientRegistry.registerKeyBinding(KeyHandler.openConfig);
        ClientRegistry.registerKeyBinding(KeyHandler.toggleDynamicLight);
        FMLCommonHandler.instance().bus().register(new KeyHandler());
    }
    
    public static File getConfigDir() {
        return new File(Loader.instance().getConfigDir(), "clientdynamiclight");
    }
    
}