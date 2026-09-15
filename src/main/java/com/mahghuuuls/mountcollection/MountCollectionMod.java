package com.mahghuuuls.mountcollection;

import com.mahghuuuls.mountcollection.forge.CommonBootstrap;
import com.mahghuuuls.mountcollection.forge.CommonProxy;
import com.mahghuuuls.mountcollection.network.MountNetwork;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = Tags.MOD_ID,
        name = Tags.MOD_NAME,
        version = Tags.VERSION,
        dependencies = "required-after:forge@[14.23.5.2847,)"
)
public final class MountCollectionMod {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);
    public static final MountNetwork NETWORK = new MountNetwork();

    @SidedProxy(
            clientSide = "com.mahghuuuls.mountcollection.client.ClientProxy",
            serverSide = "com.mahghuuuls.mountcollection.forge.CommonProxy")
    public static CommonProxy PROXY;

    private final CommonBootstrap bootstrap = new CommonBootstrap(LOGGER, NETWORK);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        bootstrap.preInitialize(event.getSuggestedConfigurationFile());
        PROXY.preInitialize(NETWORK);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        bootstrap.initialize();
        PROXY.initialize();
    }

    @Mod.EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        bootstrap.serverAboutToStart();
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        bootstrap.serverStopped();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        bootstrap.serverStarting(event);
    }
}
