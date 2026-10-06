package fixture;

@net.neoforged.fml.common.Mod("reforgedfixture")
public final class MinimalNeoMod {
    public static boolean initialized;
    public static boolean setupReceived;
    public MinimalNeoMod(net.neoforged.bus.api.IEventBus bus) {
        if (!EmbeddedNeoMod.initialized) throw new IllegalStateException("Embedded dependency must initialize first");
        bus.addListener(net.neoforged.bus.api.EventPriority.NORMAL,false,
                net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent.class,event -> setupReceived=true);
        if (Boolean.getBoolean("reforged.fixtureFail")) throw new IllegalStateException("fixture-required-construction-failure");
        initialized=true;
    }
}
