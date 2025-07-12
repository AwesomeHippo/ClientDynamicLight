package com.awesomehippo.clientdynamiclight;

import com.awesomehippo.clientdynamiclight.config.EntityConfigLoader;
import com.awesomehippo.clientdynamiclight.config.NodesConfigLoader;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.EventBus;
import net.minecraftforge.common.MinecraftForge;

@Mod(modid=ClientDynamicLight.MODID, name="Client Dynamic Light", version="1.0", acceptedMinecraftVersions="[1.7.10]")
public class ClientDynamicLight {

    public static final String MODID = "clientdynamiclight";

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        // load both configs... (still separated)
        NodesConfigLoader.INSTANCE.loadConfig();
        EntityConfigLoader.INSTANCE.loadConfig();

        EventBus bus = FMLCommonHandler.instance().bus();

        bus.register(DynamicLightHandler.INSTANCE);
        MinecraftForge.EVENT_BUS.register(DynamicLightHandler.INSTANCE);

        //bus.register(NodesDynamicLightHandler.INSTANCE);
        //MinecraftForge.EVENT_BUS.register(NodesDynamicLightHandler.INSTANCE);
    }
}