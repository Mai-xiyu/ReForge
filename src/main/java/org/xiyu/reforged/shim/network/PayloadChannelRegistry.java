package org.xiyu.reforged.shim.network;

import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkProtocol;
import net.minecraftforge.network.SimpleChannel;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.HandlerThread;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** ReForged-to-ReForged payload transport; not a native NeoForge wire protocol. */
public final class PayloadChannelRegistry {
    public enum PayloadPhase { PLAY, CONFIGURATION, COMMON }
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private static final Map<ResourceLocation, ChannelEntry<?>> PAYLOAD_INDEX = new ConcurrentHashMap<>();
    private PayloadChannelRegistry() {}

    /** The contract is immutable and independent of registration order. */
    public record Contract(PayloadPhase phase, PacketFlow flow, String version, boolean optional,
                           HandlerThread handlerThread) {
        public Contract {
            Objects.requireNonNull(phase);
            Objects.requireNonNull(handlerThread);
            if (version == null || version.isBlank() || version.length() > 128)
                throw new IllegalArgumentException("Payload version must contain 1 to 128 characters");
        }
        public boolean permits(ConnectionProtocol phase, PacketFlow direction) {
            return (flow == null || flow == direction) && switch (this.phase) {
                case PLAY -> phase == ConnectionProtocol.PLAY;
                case CONFIGURATION -> phase == ConnectionProtocol.CONFIGURATION;
                case COMMON -> phase == ConnectionProtocol.PLAY || phase == ConnectionProtocol.CONFIGURATION;
            };
        }
        public int protocolVersion() {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                        ("reforged-wire-2\n" + version + "\n" + phase + "\n" + flow).getBytes(StandardCharsets.UTF_8));
                return java.nio.ByteBuffer.wrap(hash).getInt() & Integer.MAX_VALUE;
            } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
        }
        public boolean acceptsRemote(Channel.VersionTest.Status status, int remoteVersion) {
            return status == Channel.VersionTest.Status.PRESENT ? remoteVersion == protocolVersion() : optional;
        }
    }

    public static <T extends CustomPacketPayload> void registerPayload(CustomPacketPayload.Type<T> type,
            StreamCodec<? super FriendlyByteBuf, T> codec, IPayloadHandler<T> handler, PacketFlow flow) {
        registerPayload(type, codec, handler, flow, PayloadPhase.PLAY, "1", false, HandlerThread.MAIN);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static synchronized <T extends CustomPacketPayload> void registerPayload(CustomPacketPayload.Type<T> type,
            StreamCodec<? super FriendlyByteBuf, T> codec, IPayloadHandler<T> handler, PacketFlow flow,
            PayloadPhase phase, String version, boolean optional, HandlerThread handlerThread) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(codec);
        Objects.requireNonNull(handler);
        Contract contract = new Contract(phase, flow, version, optional, handlerThread);
        if (PAYLOAD_INDEX.containsKey(type.id()))
            throw new IllegalStateException("Duplicate payload registration: " + type.id());

        // Separate channels preserve required/optional/version semantics per payload.
        ResourceLocation name = ResourceLocation.fromNamespaceAndPath(type.id().getNamespace(),
                "reforged_net/" + type.id().getPath());
        SimpleChannel channel = ChannelBuilder.named(name).networkProtocolVersion(contract.protocolVersion())
                .acceptedVersions(contract::acceptsRemote).simpleChannel();
        ChannelEntry<T> entry = new ChannelEntry<>(channel, 0, type, codec, handler, flow, phase,
                version, optional, handlerThread);
        NetworkProtocol protocol = switch (phase) {
            case PLAY -> NetworkProtocol.PLAY;
            case CONFIGURATION -> NetworkProtocol.CONFIGURATION;
            case COMMON -> null;
        };
        SimpleChannel.MessageBuilder<PayloadWrapper, FriendlyByteBuf> builder = channel.messageBuilder(
                PayloadWrapper.class, 0, protocol);
        builder.direction(flow);
        builder.encoder((wrapper, out) -> {
            if (!type.id().equals(wrapper.payloadId)) throw new IllegalArgumentException("Payload channel mismatch");
            out.writeUtf(version, 128);
            FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ((StreamCodec) codec).encode(encodingBuffer(data, phase), wrapper.payload);
                if (data.readableBytes() > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Payload exceeds bridge limit");
                byte[] bytes = new byte[data.readableBytes()];
                data.readBytes(bytes);
                out.writeByteArray(bytes);
            } finally { data.release(); }
        });
        builder.decoder(in -> {
            // Full version comparison also guards the integer handshake's hash collisions.
            if (!version.equals(in.readUtf(128))) throw new IllegalArgumentException("Payload version mismatch: " + type.id());
            return new PayloadWrapper(type.id(), in.readByteArray(MAX_PAYLOAD_BYTES));
        });
        builder.consumerNetworkThread((wrapper, context) -> {
            receive(contract, type, codec, handler, (byte[]) wrapper.payload, context);
        });
        // A failed Forge registration must abort startup, never leave a fake index entry.
        builder.add().build();
        PAYLOAD_INDEX.put(type.id(), entry);
        LOGGER.info("[ReForged] Registered payload {}: {}", type.id(), contract);
    }

    /** Shared receive path; called after Forge's channel/discriminator validation. */
    static <T extends CustomPacketPayload> void receive(Contract contract, CustomPacketPayload.Type<T> type,
            StreamCodec<? super FriendlyByteBuf,T> codec, IPayloadHandler<T> handler,
            byte[] bytes, CustomPayloadEvent.Context context) {
            Connection connection = context.getConnection();
            if (!contract.permits(connection.getProtocol(), connection.getReceiving())) {
                throw new IllegalStateException("Payload phase/direction mismatch: " + type.id());
            }
            FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
            T decoded;
            try {
                FriendlyByteBuf input = data;
                if (contract.phase() == PayloadPhase.PLAY && !(data instanceof RegistryFriendlyByteBuf)) {
                    RegistryAccess registries = context.getSender() != null ? context.getSender().registryAccess()
                            : ClientRegistries.current();
                    input = new RegistryFriendlyByteBuf(data, registries);
                }
                decoded = codec.decode(input);
                if (data.isReadable()) throw new IllegalArgumentException("Trailing payload bytes: " + type.id());
            } finally { data.release(); }
            Runnable dispatch = () -> {
                // Recheck after queued work: the connection may have changed phases.
                if (!contract.permits(connection.getProtocol(), connection.getReceiving())) return;
                handler.handle(decoded, new ReForgedPayloadContext(context));
            };
            if (contract.handlerThread() == HandlerThread.MAIN) context.enqueueWork(dispatch);
            else dispatch.run();
            context.setPacketHandled(true);
    }

    private static FriendlyByteBuf encodingBuffer(FriendlyByteBuf data, PayloadPhase phase) {
        if (phase != PayloadPhase.PLAY || data instanceof RegistryFriendlyByteBuf) return data;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        RegistryAccess access = server != null ? server.registryAccess() : ClientRegistries.current();
        return new RegistryFriendlyByteBuf(data, access);
    }
    // Keep client classes behind a side-only call for dedicated-server loading.
    private static final class ClientRegistries {
        private static RegistryAccess current() {
            var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (connection == null) throw new IllegalStateException("No client registries for play payload");
            return connection.registryAccess();
        }
    }

    public static ChannelEntry<?> getEntry(ResourceLocation id) { return PAYLOAD_INDEX.get(id); }
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        sendViaConnection(player.connection.getConnection(), payload);
    }
    public static void sendToServer(CustomPacketPayload payload) {
        var listener = net.minecraft.client.Minecraft.getInstance().getConnection();
        if (listener == null) throw new IllegalStateException("Cannot send payload without a client connection");
        sendViaConnection(listener.getConnection(), payload);
    }
    public static void sendToAllPlayers(CustomPacketPayload payload) {
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) throw new IllegalStateException("Cannot broadcast without a server");
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sendToPlayer(player, payload);
    }
    public static void sendViaConnection(Connection connection, CustomPacketPayload payload) {
        ChannelEntry<?> entry = PAYLOAD_INDEX.get(payload.type().id());
        if (entry == null) throw new IllegalStateException("Unregistered payload " + payload.type().id());
        PacketFlow outgoing = connection.getReceiving() == PacketFlow.SERVERBOUND
                ? PacketFlow.CLIENTBOUND : PacketFlow.SERVERBOUND;
        if (!entry.contract().permits(connection.getProtocol(), outgoing))
            throw new IllegalStateException("Outgoing payload phase/direction mismatch: " + payload.type().id());
        if (!entry.channel().isRemotePresent(connection)) {
            if (entry.optional()) return;
            throw new IllegalStateException("Peer lacks required payload " + payload.type().id());
        }
        entry.channel().send(new PayloadWrapper<>(payload.type().id(), payload), connection);
    }
    public static class PayloadWrapper<T> {
        final ResourceLocation payloadId;
        final T payload;
        public PayloadWrapper(ResourceLocation payloadId, T payload) {
            this.payloadId = payloadId;
            this.payload = payload;
        }
    }
    public record ChannelEntry<T extends CustomPacketPayload>(SimpleChannel channel, int discriminator,
            CustomPacketPayload.Type<T> type, StreamCodec<? super FriendlyByteBuf, T> codec, IPayloadHandler<T> handler,
            PacketFlow flow, PayloadPhase phase, String version, boolean optional, HandlerThread handlerThread) {
        public Contract contract() { return new Contract(phase, flow, version, optional, handlerThread); }
    }
}
