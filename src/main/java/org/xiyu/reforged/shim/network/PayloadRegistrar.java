package org.xiyu.reforged.shim.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.HandlerThread;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PayloadRegistrar — Shim for NeoForge's network payload registration system.
 *
 * <h3>NeoForge API</h3>
 * <pre>
 * public void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
 *     PayloadRegistrar registrar = event.registrar("1");
 *     registrar.playToServer(MyPayload.TYPE, MyPayload.STREAM_CODEC, MyPayloadHandler::handle);
 * }
 * </pre>
 *
 * <h3>Forge Bridging</h3>
 * <p>Each registered payload is bridged to a Forge {@code SimpleChannel} message via
 * {@link PayloadChannelRegistry}. The NeoForge StreamCodec is adapted to Forge's
 * encoder/decoder pattern, and handlers are wrapped with a bridged {@link ReForgedPayloadContext}.</p>
 */
public final class PayloadRegistrar {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Track all payload registrations for debugging */
    private static final Map<String, PayloadRegistrar> REGISTRARS = new ConcurrentHashMap<>();

    private final String version;
    private boolean optional;
    private HandlerThread handlerThread;

    public PayloadRegistrar(String version) {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("Payload version must not be blank");
        this.version = version;
        this.optional = false;
        this.handlerThread = HandlerThread.MAIN;
        REGISTRARS.put(version, this);
        LOGGER.debug("[ReForged] PayloadRegistrar created for version '{}'", version);
    }

    private PayloadRegistrar(PayloadRegistrar source) {
        this.version = source.version;
        this.optional = source.optional;
        this.handlerThread = source.handlerThread;
    }

    // ---- Play Phase ----

    /**
     * Register a payload to be sent from client to server (play phase).
     */
    @SuppressWarnings("unchecked")
    public <T extends CustomPacketPayload> PayloadRegistrar playToServer(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler) {
        LOGGER.info("[ReForged] PayloadRegistrar.playToServer: type={}, version={}", type.id(), version);
        register(type, (StreamCodec) codec, handler, PacketFlow.SERVERBOUND, PayloadChannelRegistry.PayloadPhase.PLAY);
        return this;
    }

    /**
     * Register a payload to be sent from server to client (play phase).
     */
    @SuppressWarnings("unchecked")
    public <T extends CustomPacketPayload> PayloadRegistrar playToClient(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler) {
        LOGGER.info("[ReForged] PayloadRegistrar.playToClient: type={}, version={}", type.id(), version);
        register(type, (StreamCodec) codec, handler, PacketFlow.CLIENTBOUND, PayloadChannelRegistry.PayloadPhase.PLAY);
        return this;
    }

    /**
     * Register a bidirectional payload (play phase).
     */
    @SuppressWarnings("unchecked")
    public <T extends CustomPacketPayload> PayloadRegistrar playBidirectional(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler) {
        LOGGER.info("[ReForged] PayloadRegistrar.playBidirectional: type={}, version={}", type.id(), version);
        register(type, (StreamCodec) codec, handler, null, PayloadChannelRegistry.PayloadPhase.PLAY);
        return this;
    }

    // ---- Configuration Phase ----

    @SuppressWarnings("unchecked")
    public <T extends CustomPacketPayload> PayloadRegistrar configurationToServer(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super FriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler) {
        LOGGER.info("[ReForged] PayloadRegistrar.configurationToServer: type={}", type.id());
        register(type, codec, handler, PacketFlow.SERVERBOUND, PayloadChannelRegistry.PayloadPhase.CONFIGURATION);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T extends CustomPacketPayload> PayloadRegistrar configurationToClient(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super FriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler) {
        LOGGER.info("[ReForged] PayloadRegistrar.configurationToClient: type={}", type.id());
        register(type, codec, handler, PacketFlow.CLIENTBOUND, PayloadChannelRegistry.PayloadPhase.CONFIGURATION);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T extends CustomPacketPayload> PayloadRegistrar configurationBidirectional(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super FriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler) {
        LOGGER.info("[ReForged] PayloadRegistrar.configurationBidirectional: type={}", type.id());
        register(type, codec, handler, null, PayloadChannelRegistry.PayloadPhase.CONFIGURATION);
        return this;
    }

    // ---- Common (Play + Configuration) ----

    @SuppressWarnings("unchecked")
    public <T extends CustomPacketPayload> PayloadRegistrar commonToServer(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super FriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler) {
        LOGGER.info("[ReForged] PayloadRegistrar.commonToServer: type={}", type.id());
        register(type, codec, handler, PacketFlow.SERVERBOUND, PayloadChannelRegistry.PayloadPhase.COMMON);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T extends CustomPacketPayload> PayloadRegistrar commonToClient(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super FriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler) {
        LOGGER.info("[ReForged] PayloadRegistrar.commonToClient: type={}", type.id());
        register(type, codec, handler, PacketFlow.CLIENTBOUND, PayloadChannelRegistry.PayloadPhase.COMMON);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T extends CustomPacketPayload> PayloadRegistrar commonBidirectional(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super FriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler) {
        LOGGER.info("[ReForged] PayloadRegistrar.commonBidirectional: type={}", type.id());
        register(type, codec, handler, null, PayloadChannelRegistry.PayloadPhase.COMMON);
        return this;
    }

    // ---- Fallback overloads accepting Object (for ASM-rewritten code with erased types) ----

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> PayloadRegistrar playToServer(Object type, Object streamCodec, Object handler) {
        LOGGER.info("[ReForged] PayloadRegistrar.playToServer (Object): type={}, version={}", type, version);
        if (type instanceof CustomPacketPayload.Type<?> t && streamCodec instanceof StreamCodec sc && handler instanceof IPayloadHandler h) {
            register((CustomPacketPayload.Type) t, sc, h, PacketFlow.SERVERBOUND, PayloadChannelRegistry.PayloadPhase.PLAY);
        } else {
            LOGGER.warn("[ReForged] PayloadRegistrar.playToServer: unrecognized types — type={}, codec={}, handler={}", 
                    type != null ? type.getClass() : null, 
                    streamCodec != null ? streamCodec.getClass() : null,
                    handler != null ? handler.getClass() : null);
        }
        return this;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> PayloadRegistrar playToClient(Object type, Object streamCodec, Object handler) {
        LOGGER.info("[ReForged] PayloadRegistrar.playToClient (Object): type={}, version={}", type, version);
        if (type instanceof CustomPacketPayload.Type<?> t && streamCodec instanceof StreamCodec sc && handler instanceof IPayloadHandler h) {
            register((CustomPacketPayload.Type) t, sc, h, PacketFlow.CLIENTBOUND, PayloadChannelRegistry.PayloadPhase.PLAY);
        } else {
            LOGGER.warn("[ReForged] PayloadRegistrar.playToClient: unrecognized types");
        }
        return this;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> PayloadRegistrar playBidirectional(Object type, Object streamCodec, Object handler) {
        LOGGER.info("[ReForged] PayloadRegistrar.playBidirectional (Object): type={}, version={}", type, version);
        if (type instanceof CustomPacketPayload.Type<?> t && streamCodec instanceof StreamCodec sc && handler instanceof IPayloadHandler h) {
            register((CustomPacketPayload.Type) t, sc, h, null, PayloadChannelRegistry.PayloadPhase.PLAY);
        } else {
            LOGGER.warn("[ReForged] PayloadRegistrar.playBidirectional: unrecognized types");
        }
        return this;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> PayloadRegistrar configurationToServer(Object type, Object streamCodec, Object handler) {
        LOGGER.info("[ReForged] PayloadRegistrar.configurationToServer (Object): type={}", type);
        if (type instanceof CustomPacketPayload.Type<?> t && streamCodec instanceof StreamCodec sc && handler instanceof IPayloadHandler h) {
            register((CustomPacketPayload.Type) t, sc, h, PacketFlow.SERVERBOUND, PayloadChannelRegistry.PayloadPhase.CONFIGURATION);
        }
        return this;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> PayloadRegistrar configurationToClient(Object type, Object streamCodec, Object handler) {
        LOGGER.info("[ReForged] PayloadRegistrar.configurationToClient (Object): type={}", type);
        if (type instanceof CustomPacketPayload.Type<?> t && streamCodec instanceof StreamCodec sc && handler instanceof IPayloadHandler h) {
            register((CustomPacketPayload.Type) t, sc, h, PacketFlow.CLIENTBOUND, PayloadChannelRegistry.PayloadPhase.CONFIGURATION);
        }
        return this;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> PayloadRegistrar configurationBidirectional(Object type, Object streamCodec, Object handler) {
        LOGGER.info("[ReForged] PayloadRegistrar.configurationBidirectional (Object): type={}", type);
        if (type instanceof CustomPacketPayload.Type<?> t && streamCodec instanceof StreamCodec sc && handler instanceof IPayloadHandler h) {
            register((CustomPacketPayload.Type) t, sc, h, null, PayloadChannelRegistry.PayloadPhase.CONFIGURATION);
        }
        return this;
    }

    // ---- Modifiers ----

    /**
     * Mark payloads registered with this registrar as optional.
     */
    public PayloadRegistrar optional() {
        PayloadRegistrar clone = new PayloadRegistrar(this);
        clone.optional = true;
        LOGGER.debug("[ReForged] PayloadRegistrar optional set for version '{}'", version);
        return clone;
    }

    /**
     * Returns a new registrar with a different version.
     */
    public PayloadRegistrar versioned(String version) {
        PayloadRegistrar result = new PayloadRegistrar(version);
        result.optional = optional;
        result.handlerThread = handlerThread;
        return result;
    }

    /**
     * Returns a new registrar that executes handlers on a specific thread.
     */
    public PayloadRegistrar executesOn(Object thread) {
        PayloadRegistrar result = new PayloadRegistrar(this);
        if (thread instanceof HandlerThread handlerThread) {
            result.handlerThread = handlerThread;
        } else {
            throw new IllegalArgumentException("Unsupported payload handler thread: " + thread);
        }
        return result;
    }

    public String getVersion() {
        return version;
    }

    public boolean isOptional() {
        return optional;
    }

    public HandlerThread getHandlerThread() {
        return handlerThread;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends CustomPacketPayload> void register(CustomPacketPayload.Type<T> type,
                                                            StreamCodec codec,
                                                            IPayloadHandler<T> handler,
                                                            PacketFlow flow,
                                                            PayloadChannelRegistry.PayloadPhase phase) {
        PayloadChannelRegistry.registerPayload(type, codec, handler, flow, phase, version, optional, handlerThread);
    }
}
