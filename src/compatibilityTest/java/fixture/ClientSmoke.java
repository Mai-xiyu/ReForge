package fixture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.nio.file.*;

@net.neoforged.fml.common.EventBusSubscriber(value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class ClientSmoke {
    private static boolean completed;
    @net.neoforged.fml.common.EventBusSubscriber(value=net.neoforged.api.distmarker.Dist.CLIENT,
            bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
    public static final class RegistrationFailure {
        public static boolean received;
        @SubscribeEvent
        public static void onGuiLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
            received = true;
            if (Boolean.getBoolean("reforged.fixtureClientFailure")) {
                throw new IllegalStateException("fixture-required-client-registration-failure");
            }
        }
    }
    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("reforged.clientSmoke") || completed || event.phase!=TickEvent.Phase.END) return;
        var client=Minecraft.getInstance();
        if (!(client.screen instanceof TitleScreen)) return;
        if (!MinimalNeoMod.initialized || !MinimalNeoMod.setupReceived || !RegistrationFailure.received) {
            throw new IllegalStateException("NeoForge fixture did not complete client initialization");
        }
        try {
            Files.writeString(Path.of("client-smoke-result.txt"),"screen=TitleScreen\nfixtureInitialized=true\ncommonSetup=true\nguiRegistration=true\n");
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot record client smoke result",failure); }
        completed=true;
        client.stop();
    }
}
