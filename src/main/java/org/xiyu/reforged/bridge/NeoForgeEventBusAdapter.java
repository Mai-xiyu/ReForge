package org.xiyu.reforged.bridge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import org.xiyu.reforged.core.NeoForgeModLoader;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Wraps a Forge {@link net.minecraftforge.eventbus.api.IEventBus} and presents it as a
 * {@link net.neoforged.bus.api.IEventBus}.
 *
 * <p>Uses {@link java.lang.reflect.Proxy} to dynamically implement the interface,
 * delegating all calls to the underlying Forge event bus while also handling
 * NeoForge's {@code @SubscribeEvent} annotations.</p>
 *
 * <h3>Event wrapping</h3>
 * <p>NeoForge event shims (in package {@code net.neoforged}) may NOT extend Forge's
 * {@link Event} class. Instead, they wrap Forge events via a single-arg constructor.
 * When a NeoForge mod registers a handler for such a type, this adapter:</p>
 * <ol>
 *   <li>Discovers the Forge event type from the wrapper's constructor</li>
 *   <li>Registers a Forge-side listener for that type</li>
 *   <li>When the Forge event fires, instantiates the NeoForge wrapper and calls the handler</li>
 * </ol>
 */
public final class NeoForgeEventBusAdapter {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static int poseRestoreLogCount;

    /**
     * Fallback listener store for events that cannot be registered on Forge's EventBus.
     * This handles Flywheel/NeoForge custom events that extend net.neoforged.bus.api.Event
     * (rewritten to net.minecraftforge.eventbus.api.Event) but lack proper Forge ListenerList setup.
     */
    private static final Map<Class<?>, CopyOnWriteArrayList<FallbackRegistration>> FALLBACK_LISTENERS =
            new ConcurrentHashMap<>();

    private record FallbackRegistration(IEventBus bus, Object owner, Class<?> eventType, Consumer<Object> listener,
                                        EventPriority priority, boolean receiveCancelled) {}

    /**
     * Dispatch an event to fallback listeners. Called by post() interception and ModLoader.postEvent().
     */
    public static boolean dispatchFallback(Object event) {
        IEventBus bus = NeoForgeModLoader.getForgeModBus();
        return bus != null && dispatchFallback(bus, event);
    }

    public static boolean dispatchFallback(IEventBus bus, Object event) {
        if (event == null) return false;
        List<FallbackRegistration> listeners = matchingFallbackListeners(bus, event);
        if (listeners.isEmpty()) return false;
        listeners.sort(Comparator.comparingInt(registration -> registration.priority().ordinal()));
        for (FallbackRegistration registration : listeners) {
            if (event instanceof Event forgeEvent && forgeEvent.isCancelable()
                    && forgeEvent.isCanceled() && !registration.receiveCancelled()) {
                continue;
            }
            try {
                invokeConsumerWithPoseGuard(registration.listener(), event);
            } catch (Throwable t) {
                throw handlerFailure("Fallback listener for " + event.getClass().getName(), t);
            }
        }
        return event instanceof Event forgeEvent && forgeEvent.isCancelable() && forgeEvent.isCanceled();
    }

    private static List<FallbackRegistration> matchingFallbackListeners(IEventBus bus, Object event) {
        List<FallbackRegistration> listeners = new ArrayList<>();
        for (var entry : FALLBACK_LISTENERS.entrySet()) {
            if (entry.getKey().isAssignableFrom(event.getClass())) {
                for (FallbackRegistration registration : entry.getValue())
                    if (registration.bus() == bus) listeners.add(registration);
            }
        }
        return listeners;
    }

    /**
     * Create a dynamic proxy that implements {@code net.neoforged.bus.api.IEventBus}
     * and delegates all calls to the given Forge event bus.
     */
    public static net.neoforged.bus.api.IEventBus wrap(net.minecraftforge.eventbus.api.IEventBus delegate) {
        return (net.neoforged.bus.api.IEventBus) Proxy.newProxyInstance(
                NeoForgeEventBusAdapter.class.getClassLoader(),
                new Class<?>[]{net.neoforged.bus.api.IEventBus.class},
                (proxy, method, args) -> {
                    String name = method.getName();

                    // Special handling for register() — scan for NeoForge annotations
                    if ("register".equals(name) && args != null && args.length == 1) {
                        handleRegister(delegate, args[0]);
                        return null;
                    }

                    // Intercept ALL addListener() calls — NeoForge events need wrapping
                    // before they can be registered on the Forge bus.  Forge's EventBus
                    // cannot resolve NeoForge event types from Consumer generics.
                    if ("addListener".equals(name) && args != null && args.length > 0) {
                        handleAddListener(delegate, args);
                        return null;
                    }

                    // Intercept post() — dispatch to fallback listeners for non-Forge events,
                    // then also delegate to Forge's bus for normal events.
                    if ("post".equals(name) && args != null && args.length == 1) {
                        Object event = args[0];
                        boolean canceled = dispatchFallback(delegate, event);
                        // Also delegate to Forge bus if the event is a Forge Event
                        if (event instanceof Event forgeEvent) {
                            canceled = delegate.post(forgeEvent) || canceled;
                        }
                        // Return type depends on which post() overload was invoked:
                        // Forge's post(Event) returns boolean, NeoForge's post(Event) returns Event.
                        // The Dynamic Proxy will auto-unbox the return for primitive types,
                        // so we MUST return a Boolean when the resolved method returns boolean.
                        if (method.getReturnType() == boolean.class) {
                            return canceled;
                        }
                        return event;
                    }

                    if ("unregister".equals(name) && args != null && args.length == 1) {
                        unregisterFallback(delegate, args[0]);
                        unregisterBridged(delegate, args[0]);
                        try {
                            Method delegateMethod = findMatchingMethod(delegate.getClass(), method);
                            if (delegateMethod != null) {
                                delegateMethod.setAccessible(true);
                                return delegateMethod.invoke(delegate, args);
                            }
                        } catch (Throwable t) {
                            LOGGER.debug("[ReForged] Delegating unregister() failed: {}", t.getMessage());
                        }
                        return null;
                    }

                    // Invoke default methods on the NeoForge IEventBus interface directly.
                    if (method.isDefault()) {
                        return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    }

                    // Delegate all other calls to the Forge event bus
                    try {
                        Method delegateMethod = findMatchingMethod(delegate.getClass(), method);
                        if (delegateMethod != null) {
                            delegateMethod.setAccessible(true);
                            return delegateMethod.invoke(delegate, args);
                        }
                    } catch (Throwable t) {
                        LOGGER.debug("[ReForged] Delegating {}() failed: {}", name, t.getMessage());
                    }

                    // Fallback for Object methods
                    if ("toString".equals(name)) return "NeoForgeEventBusAdapter[" + delegate + "]";
                    if ("hashCode".equals(name)) return delegate.hashCode();
                    if ("equals".equals(name)) return proxy == args[0];

                    return null;
                }
        );
    }

    /**
     * Handle register() calls by scanning for both Forge and NeoForge @SubscribeEvent.
     * Public so it can be called from {@code NeoForgeModLoader} for {@code @EventBusSubscriber} auto-registration.
     *
     * <p>We intentionally do NOT delegate to {@code Forge EventBus.register()} because
     * Forge's ASM code generation ({@code ModLauncherFactory}) creates dynamic classes
     * that {@code NeoModClassLoader} cannot load, causing all handlers to fail.
     * Instead, we scan all annotated methods ourselves and register them via
     * reflection-based dispatch through {@code addListener()}.</p>
     *
     * <p>After bytecode rewriting, NeoForge's {@code @SubscribeEvent} annotation descriptor
     * is remapped to Forge's {@code @SubscribeEvent}. We therefore check for BOTH
     * annotation types to cover both rewritten and unrewritten classes.</p>
     */
    public static void handleRegister(net.minecraftforge.eventbus.api.IEventBus delegate, Object target) {
        Class<?> clazz = target instanceof Class<?> c ? c : target.getClass();
        Method[] methods;
        try {
            methods = clazz.getDeclaredMethods();
        } catch (Throwable t) {
            throw handlerFailure("Cannot scan subscriber " + clazz.getName(), t);
        }

        int registered = 0;
        for (Method method : methods) {
            try {
                EventPriority priority = EventPriority.NORMAL;
                boolean receiveCancelled = false;
                boolean found = false;

                // Check NeoForge @SubscribeEvent (unrewritten bytecode)
                if (method.isAnnotationPresent(net.neoforged.bus.api.SubscribeEvent.class)) {
                    net.neoforged.bus.api.SubscribeEvent ann =
                            method.getAnnotation(net.neoforged.bus.api.SubscribeEvent.class);
                    priority = ann.priority().toForge();
                    receiveCancelled = ann.receiveCanceled();
                    found = true;
                }
                // Check Forge @SubscribeEvent (rewritten bytecode)
                else if (method.isAnnotationPresent(net.minecraftforge.eventbus.api.SubscribeEvent.class)) {
                    net.minecraftforge.eventbus.api.SubscribeEvent ann =
                            method.getAnnotation(net.minecraftforge.eventbus.api.SubscribeEvent.class);
                    priority = ann.priority();
                    receiveCancelled = ann.receiveCanceled();
                    found = true;
                }

                if (!found) continue;

                if (registerEventHandler(delegate, target, method, priority, receiveCancelled)) {
                    registered++;
                }
            } catch (Throwable t) {
                throw handlerFailure("Cannot register subscriber " + clazz.getName() + "." + method.getName(), t);
            }
        }

        if (registered > 0) {
            LOGGER.info("[ReForged] Registered {} event handler(s) for {}",
                    registered, clazz.getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean registerEventHandler(net.minecraftforge.eventbus.api.IEventBus delegate,
                                                Object target, Method method,
                                                EventPriority priority, boolean receiveCancelled) {
        try {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1) return false;

            method.setAccessible(true);

            // Case 1 (preferred): Check if the parameter type is a NeoForge wrapper that
            // has a constructor taking a Forge Event subclass. This maps the NeoForge event
            // to the correct Forge event type so the handler fires when Forge dispatches it.
            Class<?> neoType = params[0];
            Constructor<?> wrapperCtor = findWrapperConstructor(neoType);
            if (wrapperCtor != null) {
                Class<? extends Event> forgeEventType = (Class<? extends Event>) wrapperCtor.getParameterTypes()[0];
                wrapperCtor.setAccessible(true);
                Object invokeTarget = target instanceof Class<?> ? null : target;

                final String handlerDesc = method.getDeclaringClass().getSimpleName() + "." + method.getName();
                final String forgeEventName = forgeEventType.getSimpleName();
                Consumer<Event> bridgeHandler = forgeEvent -> {
                    if (!forgeEventType.isInstance(forgeEvent)) return;
                    try {
                        Object neoEvent = wrapperCtor.newInstance(forgeEvent);
                        invokeMethodWithPoseGuard(method, invokeTarget, neoEvent);
                        // Propagate cancellation from NeoForge wrapper back to Forge event
                        if (neoEvent instanceof Event neoEvt && neoEvt.isCanceled()
                                && forgeEvent.isCancelable()) {
                            forgeEvent.setCanceled(true);
                        }
                    } catch (Throwable t) {
                        throw handlerFailure("Wrapped subscriber " + method, t);
                    }
                };

                // Route mod bus events to the Forge MOD bus
                boolean isModBusEvent = net.minecraftforge.fml.event.IModBusEvent.class.isAssignableFrom(forgeEventType);
                IEventBus targetBus = delegate;
                if (isModBusEvent) {
                    IEventBus modBus = NeoForgeModLoader.getForgeModBus();
                    if (modBus != null) targetBus = modBus;
                }
                addBridged(delegate, targetBus, target, priority, receiveCancelled, forgeEventType, bridgeHandler);
                LOGGER.info("[ReForged] Registered wrapped @SubscribeEvent: {}.{}({}) \u2192 Forge: {}{}",
                        method.getDeclaringClass().getSimpleName(), method.getName(),
                        neoType.getSimpleName(), forgeEventType.getSimpleName(),
                        isModBusEvent ? " (MOD bus)" : "");
                return true;
            }

            // Case 2 (fallback): The parameter type IS a Forge Event subclass with no
            // wrapper constructor → register directly. This handles cases where
            // NeoForge shims extend Event directly (e.g. stub events).
            // We use the EXACT type as filter to avoid dispatching unrelated subtypes.
            if (Event.class.isAssignableFrom(neoType)) {
                Class<? extends Event> eventType = (Class<? extends Event>) neoType;

                // Route mod bus events to the Forge MOD bus (same logic as Case 1).
                // NeoForge determines target bus per-method based on event type, not
                // per-class annotation attribute. IModBusEvent → MOD bus, else → delegate.
                boolean isModBusEvent = net.minecraftforge.fml.event.IModBusEvent.class.isAssignableFrom(eventType);
                IEventBus targetBus = delegate;
                if (isModBusEvent) {
                    IEventBus modBus = NeoForgeModLoader.getForgeModBus();
                    if (modBus != null) targetBus = modBus;
                }

                Object invokeTarget = target instanceof Class<?> ? null : target;
                Class<?> finalNeoType = neoType;
                Consumer<Event> directHandler = event -> {
                    if (!finalNeoType.isInstance(event)) return;
                    try { invokeMethodWithPoseGuard(method, invokeTarget, event); }
                    catch (Throwable t) { throw handlerFailure("Subscriber " + method, t); }
                };
                try {
                    addBridged(delegate, targetBus, target, priority, receiveCancelled, eventType, directHandler);
                    LOGGER.info("[ReForged] Registered direct NeoForge @SubscribeEvent: {}.{}({}){}",
                            method.getDeclaringClass().getSimpleName(), method.getName(), neoType.getSimpleName(),
                            isModBusEvent ? " (MOD bus)" : "");
                } catch (Throwable t) {
                    registerFallback(targetBus, target, eventType,
                            event -> {
                                if (event instanceof Event forgeEvent) {
                                    directHandler.accept(forgeEvent);
                                }
                            }, priority, receiveCancelled);
                    LOGGER.info("[ReForged] Forge bus registration failed for @SubscribeEvent {}.{}({}) - using fallback listener: {}",
                            method.getDeclaringClass().getSimpleName(), method.getName(), neoType.getSimpleName(),
                            t.getMessage());
                }
                return true;
            }

            LOGGER.warn("[ReForged] Could not register handler {}.{} — parameter type {} " +
                            "is neither a Forge Event nor a NeoForge wrapper",
                    method.getDeclaringClass().getSimpleName(), method.getName(), neoType.getName());
        } catch (Throwable t) {
            throw handlerFailure("Cannot register handler " + method, t);
        }
        return false;
    }

    /**
     * Find a single-arg constructor on {@code neoType} whose parameter is a Forge {@link Event} subclass.
     * This establishes the NeoForge wrapper → Forge event mapping by convention.
     *
     * @return the wrapper constructor, or {@code null} if none found
     */
    private static Constructor<?> findWrapperConstructor(Class<?> neoType) {
        for (Constructor<?> ctor : neoType.getDeclaredConstructors()) {
            Class<?>[] ctorParams = ctor.getParameterTypes();
            if (ctorParams.length == 1 && Event.class.isAssignableFrom(ctorParams[0])) {
                return ctor;
            }
        }
        return null;
    }

    /**
     * Intercept all addListener() calls and bridge NeoForge event types to Forge.
     * Parses all NeoForge addListener overloads (8 variants), extracts the event type
     * from an explicit Class arg or via TypeResolver on the Consumer generic, then
     * registers the appropriate Forge listener with wrapper logic.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void handleAddListener(net.minecraftforge.eventbus.api.IEventBus delegate, Object[] args) {
        EventPriority priority = EventPriority.NORMAL;
        boolean receiveCancelled = false;
        Class<?> eventType = null;
        Consumer<?> consumer = null;

        // The Consumer is always the last argument in all addListener overloads
        if (args[args.length - 1] instanceof Consumer<?> c) {
            consumer = c;
        }
        if (consumer == null) {
            throw new IllegalArgumentException("addListener requires a Consumer argument");
        }

        // Parse remaining args (priority, receiveCancelled, eventType)
        for (int i = 0; i < args.length - 1; i++) {
            Object arg = args[i];
            if (arg instanceof net.neoforged.bus.api.EventPriority nep) {
                priority = nep.toForge();
            } else if (arg instanceof EventPriority fp) {
                priority = fp;
            } else if (arg instanceof Boolean b) {
                receiveCancelled = b;
            } else if (arg instanceof Class<?> c) {
                eventType = c;
            }
        }

        // If event type not explicitly provided, extract from Consumer's generic type
        if (eventType == null) {
            eventType = extractEventTypeFromConsumer(consumer);
        }
        if (eventType == null) {
            throw new IllegalArgumentException("Cannot determine addListener event type for " + consumer.getClass().getName());
        }

        // Case 1: NeoForge wrapper event (has constructor taking a Forge Event subclass)
        LOGGER.info("[ReForged] addListener: eventType={} (pkg={}), isForgeEvent={}, classLoader={}",
                eventType.getName(), eventType.getPackageName(),
                Event.class.isAssignableFrom(eventType),
                eventType.getClassLoader());
        Constructor<?> wrapperCtor = findWrapperConstructor(eventType);

        // Case 1b: DISABLED — When TypeResolver returns a Forge type, it means the
        // consumer's bytecode was remapped from NeoForge → Forge types. The consumer
        // expects the Forge type. Wrapping in a NeoForge wrapper causes ClassCastException
        // because the wrapper does not extend the Forge event class.
        // Fall through to Case 2 (direct registration) instead.

        LOGGER.info("[ReForged] addListener: wrapperCtor={} for {}", wrapperCtor, eventType.getSimpleName());
        if (wrapperCtor != null) {
            Constructor<?> finalWrapperCtor = wrapperCtor;
            Class<? extends Event> forgeEventType = (Class<? extends Event>) finalWrapperCtor.getParameterTypes()[0];
            finalWrapperCtor.setAccessible(true);
            Consumer<?> finalConsumer = consumer;
            Class<?> finalEventType = eventType;

            Consumer<Event> bridgeListener = forgeEvent -> {
                // Guard: Forge EventBus may dispatch events of unexpected types if
                // ListenerList parent chains are shared. Skip if the event is not
                // the expected Forge type (e.g. a NeoForge wrapper posted separately).
                if (!forgeEventType.isInstance(forgeEvent)) return;
                try {
                    Object neoEvent = finalWrapperCtor.newInstance(forgeEvent);
                    invokeConsumerWithPoseGuard(finalConsumer, neoEvent);
                    // Propagate cancellation from NeoForge wrapper back to Forge event
                    if (neoEvent instanceof Event neoEvt && neoEvt.isCanceled()
                            && forgeEvent.isCancelable()) {
                        forgeEvent.setCanceled(true);
                    }
                } catch (Throwable t) {
                    throw handlerFailure("Wrapped listener for " + finalEventType.getName(), t);
                }
            };

            // Determine which Forge bus to register on:
            // IModBusEvent events are fired on the Forge MOD bus, not the GAME bus.
            boolean isModBusEvent = net.minecraftforge.fml.event.IModBusEvent.class.isAssignableFrom(forgeEventType);
            IEventBus targetBus = delegate;
            if (isModBusEvent) {
                IEventBus modBus = NeoForgeModLoader.getForgeModBus();
                if (modBus != null) {
                    targetBus = modBus;
                    LOGGER.info("[ReForged] Routing addListener for {} to MOD bus (was on {})",
                            eventType.getSimpleName(), delegate == modBus ? "mod bus" : "game bus");
                }
            }
            addBridged(delegate, targetBus, consumer, priority, receiveCancelled, forgeEventType, bridgeListener);
            LOGGER.info("[ReForged] Registered wrapped addListener: {} → {}{}",
                    eventType.getSimpleName(), forgeEventType.getSimpleName(),
                    isModBusEvent ? " (MOD bus)" : "");
            return;
        }

        // Case 2: Direct Forge Event subclass — register directly on Forge bus
        // Use instanceof guard to only dispatch exact type matches
        if (Event.class.isAssignableFrom(eventType)) {
            Class<?> finalEventType2 = eventType;
            Consumer<?> finalConsumer2 = consumer;
            try {
                addBridged(delegate, delegate, consumer, priority, receiveCancelled,
                    eventType, (Consumer<Event>) event -> {
                        if (!finalEventType2.isInstance(event)) return;
                        try {
                            invokeConsumerWithPoseGuard(finalConsumer2, event);
                        } catch (Throwable t2) {
                            throw handlerFailure("Listener for " + finalEventType2.getName(), t2);
                        }
                    });
                LOGGER.info("[ReForged] Registered direct addListener for {}", eventType.getSimpleName());
            } catch (Throwable t) {
                // Forge's EventBus can't compute listener lists for non-native events
                // (e.g. Flywheel's custom events). Store in fallback map instead.
                LOGGER.info("[ReForged] Forge bus registration failed for {} — using fallback listener: {}",
                        eventType.getName(), t.getMessage());
                registerFallback(delegate, finalConsumer2, finalEventType2,
                        event -> {
                            if (!finalEventType2.isInstance(event)) return;
                            try {
                                invokeConsumerWithPoseGuard(finalConsumer2, event);
                            } catch (Throwable t2) {
                                throw handlerFailure("Fallback listener for " + finalEventType2.getName(), t2);
                            }
                        }, priority, receiveCancelled);
            }
            return;
        }

        throw new IllegalArgumentException("Unsupported addListener event type " + eventType.getName());
    }

    private static RuntimeException handlerFailure(String context, Throwable failure) {
        if (failure instanceof java.lang.reflect.InvocationTargetException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof Error error) throw error;
        return new IllegalStateException(context, failure);
    }

    private record BridgedRegistration(IEventBus sourceBus, IEventBus targetBus, Object owner, Consumer<?> listener) {}
    private static final CopyOnWriteArrayList<BridgedRegistration> BRIDGED_LISTENERS = new CopyOnWriteArrayList<>();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addBridged(IEventBus source, IEventBus target, Object owner, EventPriority priority,
                                   boolean receiveCancelled, Class<?> type, Consumer<?> listener) {
        target.addListener(priority, receiveCancelled, (Class) type, (Consumer) listener);
        BRIDGED_LISTENERS.add(new BridgedRegistration(source, target, owner, listener));
    }

    private static void unregisterBridged(IEventBus source, Object owner) {
        BRIDGED_LISTENERS.removeIf(entry -> {
            if (entry.sourceBus() != source || entry.owner() != owner) return false;
            entry.targetBus().unregister(entry.listener());
            return true;
        });
    }

    private static void registerFallback(IEventBus bus, Object owner, Class<?> eventType, Consumer<Object> listener,
                                         EventPriority priority, boolean receiveCancelled) {
        FALLBACK_LISTENERS.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>())
                .add(new FallbackRegistration(bus, owner, eventType, listener, priority, receiveCancelled));
    }

    private static void unregisterFallback(IEventBus bus, Object owner) {
        if (owner == null) return;
        FALLBACK_LISTENERS.values().forEach(list -> list.removeIf(entry -> entry.bus() == bus && entry.owner() == owner));
        FALLBACK_LISTENERS.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static void invokeWithNeoContext(Consumer<?> consumer, Object event) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        ClassLoader neoLoader = NeoForgeModLoader.getNeoModClassLoader();
        if (neoLoader == null && consumer != null) {
            neoLoader = consumer.getClass().getClassLoader();
        }

        boolean changed = neoLoader != null && neoLoader != previous;
        try {
            if (changed) {
                thread.setContextClassLoader(neoLoader);
            }
            ((Consumer<Object>) consumer).accept(event);
        } finally {
            if (changed) {
                thread.setContextClassLoader(previous);
            }
        }
    }

    private static void invokeConsumerWithPoseGuard(Consumer<?> consumer, Object event) {
        int poseDepth = capturePoseStackDepth(event);
        try {
            invokeWithNeoContext(consumer, event);
        } finally {
            restorePoseStackDepth(event, poseDepth);
        }
    }

    private static void invokeMethodWithPoseGuard(Method method, Object target, Object event) throws Throwable {
        int poseDepth = capturePoseStackDepth(event);
        try {
            method.invoke(target, event);
        } finally {
            restorePoseStackDepth(event, poseDepth);
        }
    }

    private static int capturePoseStackDepth(Object event) {
        Object poseStack = extractPoseStack(event);
        if (!(poseStack instanceof com.mojang.blaze3d.vertex.PoseStack ps)) return -1;
        return getPoseStackDepth(ps);
    }

    private static void restorePoseStackDepth(Object event, int targetDepth) {
        if (targetDepth < 0) return;
        Object poseStack = extractPoseStack(event);
        if (!(poseStack instanceof com.mojang.blaze3d.vertex.PoseStack ps)) return;
        int current = getPoseStackDepth(ps);
        int restored = 0;
        while (current > targetDepth) {
            try {
                ps.popPose();
            } catch (Throwable ignored) {
                break;
            }
            current--;
            restored++;
        }
        if (restored > 0 && poseRestoreLogCount++ < 8) {
            LOGGER.warn("[ReForged] Restored {} leaked PoseStack frame(s) after NeoForge {} handler",
                    restored, event.getClass().getSimpleName());
        }
    }

    private static Object extractPoseStack(Object event) {
        if (event == null) return null;
        try {
            Method getter = event.getClass().getMethod("getPoseStack");
            if (getter.getParameterCount() == 0) {
                return getter.invoke(event);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int getPoseStackDepth(com.mojang.blaze3d.vertex.PoseStack poseStack) {
        try {
            for (java.lang.reflect.Field field : com.mojang.blaze3d.vertex.PoseStack.class.getDeclaredFields()) {
                if (java.util.Deque.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return ((java.util.Deque<?>) field.get(poseStack)).size();
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    /**
     * Extract the event type T from a Consumer&lt;T&gt; using Forge's TypeResolver.
     * Uses reflection to access typetools (transitive dependency of Forge EventBus).
     */
    private static Class<?> extractEventTypeFromConsumer(Consumer<?> consumer) {
        try {
            Class<?> resolverClass = Class.forName("net.jodah.typetools.TypeResolver");
            java.lang.reflect.Method resolveMethod = resolverClass.getMethod(
                    "resolveRawArgument", Class.class, Class.class);
            Class<?> type = (Class<?>) resolveMethod.invoke(null, Consumer.class, consumer.getClass());
            // Check for TypeResolver.Unknown sentinel
            Class<?> unknownClass = Class.forName("net.jodah.typetools.TypeResolver$Unknown");
            if (type == unknownClass) {
                return null;
            }
            return type;
        } catch (Throwable t) {
            LOGGER.debug("[ReForged] Failed to resolve event type from Consumer: {}", t.getMessage());
            return null;
        }
    }

    /**
     * Find a method on the delegate class that matches the proxy method's name and parameter types.
     */
    private static Method findMatchingMethod(Class<?> clazz, Method proxyMethod) {
        try {
            return clazz.getMethod(proxyMethod.getName(), proxyMethod.getParameterTypes());
        } catch (NoSuchMethodException e) {
            // Try with broader search
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(proxyMethod.getName()) &&
                        m.getParameterCount() == proxyMethod.getParameterCount()) {
                    return m;
                }
            }
            return null;
        }
    }

}
