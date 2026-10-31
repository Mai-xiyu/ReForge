package fixture;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod("reforgedserver")
public final class ServerOnlyNeoMod {
    public static boolean initialized;
    public static boolean setupReceived;

    public ServerOnlyNeoMod(IEventBus bus) {
        initialized = true;
        bus.addListener(FMLCommonSetupEvent.class, event -> setupReceived = true);
    }
}
