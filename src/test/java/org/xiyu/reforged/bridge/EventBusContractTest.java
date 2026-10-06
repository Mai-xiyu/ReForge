package org.xiyu.reforged.bridge;

import net.minecraftforge.eventbus.api.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class EventBusContractTest {
    @Cancelable public static class Parent extends Event {}
    public static class Child extends Parent {}
    static IEventBus rejectingBus() {
        return (IEventBus) Proxy.newProxyInstance(IEventBus.class.getClassLoader(),new Class<?>[]{IEventBus.class},
                (proxy,method,args) -> {
                    if (method.getName().equals("addListener")) throw new IllegalArgumentException("Force fallback");
                    if (method.getName().equals("post")) return ((Event)args[0]).isCanceled();
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy==args[0];
                    if (method.getName().equals("toString")) return "FixtureBus";
                    return null;
                });
    }
    @Test void fallbackPreservesPriorityInheritanceCancelAndUnregister() {
        IEventBus delegate=rejectingBus(); var bus=NeoForgeEventBusAdapter.wrap(delegate);
        List<String> trace=new ArrayList<>();
        Consumer<Parent> normal=e -> trace.add("normal");
        Consumer<Parent> high=e -> { trace.add("high"); e.setCanceled(true); };
        Consumer<Parent> canceled=e -> trace.add("canceled");
        bus.addListener(EventPriority.NORMAL,false,Parent.class,normal);
        bus.addListener(EventPriority.HIGH,false,Parent.class,high);
        bus.addListener(EventPriority.LOW,true,Parent.class,canceled);
        assertTrue(bus.post((Event)new Child()));
        assertEquals(List.of("high","canceled"),trace);
        bus.unregister(high); bus.unregister(canceled); trace.clear();
        assertFalse(bus.post((Event)new Child())); assertEquals(List.of("normal"),trace);
        bus.unregister(normal);
    }
    @Test void fallbackIsIsolatedByBus() {
        var a=NeoForgeEventBusAdapter.wrap(rejectingBus()); var b=NeoForgeEventBusAdapter.wrap(rejectingBus());
        List<String> trace=new ArrayList<>();
        Consumer<Parent> first=e -> trace.add("A"), second=e -> trace.add("B");
        a.addListener(EventPriority.NORMAL,false,Parent.class,first);
        b.addListener(EventPriority.NORMAL,false,Parent.class,second);
        a.post((Event)new Child()); assertEquals(List.of("A"),trace);
        a.unregister(first); trace.clear(); b.post((Event)new Child()); assertEquals(List.of("B"),trace);
        b.unregister(second);
    }
    @Test void realForgePathReturnsCancellationAndUnregistersBridge() {
        IEventBus delegate=BusBuilder.builder().build(); var bus=NeoForgeEventBusAdapter.wrap(delegate);
        Consumer<Parent> handler=e -> e.setCanceled(true);
        bus.addListener(EventPriority.NORMAL,false,Parent.class,handler);
        assertTrue(bus.post((Event)new Parent()));
        bus.unregister(handler); assertFalse(bus.post((Event)new Parent()));
    }
    @Test void bytecodeHelperPostsOnSuppliedBus() {
        var bus=NeoForgeEventBusAdapter.wrap(rejectingBus());
        Consumer<Parent> handler=e -> e.setCanceled(true);
        bus.addListener(EventPriority.NORMAL,false,Parent.class,handler);
        Parent event=new Parent(); assertSame(event,EventBusHelper.postAndReturn(bus,event)); assertTrue(event.isCanceled());
        bus.unregister(handler);
    }
    @Test void realForgeListenerFailurePropagatesAndRestoresContext() {
        var bus=NeoForgeEventBusAdapter.wrap(BusBuilder.builder().build());
        ClassLoader previous=Thread.currentThread().getContextClassLoader();
        Consumer<Parent> broken=e -> { throw new IllegalStateException("fixture-listener-failure"); };
        bus.addListener(EventPriority.NORMAL,false,Parent.class,broken);
        assertThrows(IllegalStateException.class, () -> bus.post((Event)new Parent()));
        assertSame(previous,Thread.currentThread().getContextClassLoader());
        bus.unregister(broken);
    }
    @Test void fallbackFailureCannotReportSuccessfulDispatch() {
        var bus=NeoForgeEventBusAdapter.wrap(rejectingBus());
        Consumer<Parent> broken=e -> { throw new IllegalStateException("fixture-fallback-failure"); };
        bus.addListener(EventPriority.NORMAL,false,Parent.class,broken);
        assertThrows(IllegalStateException.class, () -> bus.post((Event)new Parent()));
        bus.unregister(broken);
    }
}
