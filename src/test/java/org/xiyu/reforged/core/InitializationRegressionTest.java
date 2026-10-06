package org.xiyu.reforged.core;

import net.minecraftforge.eventbus.api.*;
import org.junit.jupiter.api.Test;
import org.xiyu.reforged.bridge.NeoForgeEventBusAdapter;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class InitializationRegressionTest {
    @TempDir Path dir;
    public static class FixtureEvent extends Event {}
    public static class BrokenMod {
        public BrokenMod(net.neoforged.bus.api.IEventBus bus) {
            bus.addListener(EventPriority.NORMAL,false,FixtureEvent.class,event -> {});
            throw new IllegalStateException("fixture-construction-failure");
        }
    }
    public static class UnsupportedMod { public UnsupportedMod(String unsupported) {} }
    @Test void constructionFailurePropagatesDespiteEarlierSideEffects() {
        IEventBus forge=BusBuilder.builder().build(); var neo=NeoForgeEventBusAdapter.wrap(forge);
        var error=assertThrows(java.lang.reflect.InvocationTargetException.class, () -> NeoModInstantiator.instantiateMod(BrokenMod.class,neo,forge));
        assertEquals("fixture-construction-failure",error.getCause().getMessage());
    }
    @Test void unsupportedRequiredConstructorCannotReturnNull() {
        IEventBus forge=BusBuilder.builder().build(); var neo=NeoForgeEventBusAdapter.wrap(forge);
        assertThrows(IllegalStateException.class, () -> NeoModInstantiator.instantiateMod(UnsupportedMod.class,neo,forge));
    }
    @Test void malformedClassCannotHideRequiredEntrypoint() throws Exception {
        Path path=dir.resolve("broken.jar");
        try (var output=new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry("fixture/Broken.class")); output.write(new byte[]{0,1,2}); output.closeEntry();
        }
        assertThrows(IllegalStateException.class, () -> NeoModScanner.scanForModClasses(path));
        assertThrows(IllegalStateException.class, () -> NeoModScanner.scanJarAnnotations(path));
    }
}
