package fixture;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod("reforgedclient")
public final class ClientOnlyNeoMod {
    public static boolean initialized;
    public static boolean setupReceived;

    public ClientOnlyNeoMod(IEventBus bus) {
        initialized = true;
        bus.addListener(FMLClientSetupEvent.class, event -> {
            Minecraft.getInstance();
            setupReceived = true;
        });
    }
}
