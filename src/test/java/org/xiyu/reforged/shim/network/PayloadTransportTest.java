package org.xiyu.reforged.shim.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.xiyu.reforged.shim.network.PayloadChannelRegistry.*;

class PayloadTransportTest {
    record Data(int value,CustomPacketPayload.Type<Data> type) implements CustomPacketPayload {}
    static class FixtureConnection extends Connection {
        final ConnectionProtocol phase;
        FixtureConnection(PacketFlow direction,ConnectionProtocol phase) { super(direction); this.phase=phase; }
        @Override public ConnectionProtocol getProtocol() { return phase; }
    }
    static class Context extends CustomPayloadEvent.Context {
        Runnable queued;
        Context(PacketFlow flow,ConnectionProtocol phase) { super(new FixtureConnection(flow,phase)); }
        @Override public java.util.concurrent.CompletableFuture<Void> enqueueWork(Runnable task) {
            queued=task; return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    @Test void transportRejectsWrongFlowBeforeCodecAndHonorsThreadChoice() throws Exception {
        for (HandlerThread thread : HandlerThread.values()) {
            ResourceLocation id=ResourceLocation.fromNamespaceAndPath("test","packet"+UUID.randomUUID().toString().replace("-",""));
            var type=new CustomPacketPayload.Type<Data>(id);
            var decodes=new java.util.concurrent.atomic.AtomicInteger();
            var handles=new java.util.concurrent.atomic.AtomicInteger();
            StreamCodec<FriendlyByteBuf,Data> codec=new StreamCodec<>() {
                public Data decode(FriendlyByteBuf buffer) { decodes.incrementAndGet(); return new Data(buffer.readInt(),type); }
                public void encode(FriendlyByteBuf buffer,Data data) { buffer.writeInt(data.value()); }
            };
            Contract contract = new Contract(PayloadPhase.COMMON,PacketFlow.SERVERBOUND,"1",false,thread);
            BiConsumer<PayloadWrapper,CustomPayloadEvent.Context> consumer=(wrapper,context) ->
                    receive(contract,type,codec,(data,ctx) -> handles.addAndGet(data.value()),(byte[])wrapper.payload,context);
            byte[] encoded=java.nio.ByteBuffer.allocate(4).putInt(3).array();
            var wrapper=new PayloadWrapper(id,encoded);
            assertThrows(IllegalStateException.class, () -> consumer.accept(wrapper,new Context(PacketFlow.CLIENTBOUND,ConnectionProtocol.PLAY)));
            assertThrows(IllegalStateException.class, () -> consumer.accept(wrapper,new Context(PacketFlow.SERVERBOUND,ConnectionProtocol.LOGIN)));
            assertEquals(0,decodes.get()); assertEquals(0,handles.get());
            Context valid=new Context(PacketFlow.SERVERBOUND,ConnectionProtocol.PLAY); consumer.accept(wrapper,valid);
            assertEquals(1,decodes.get());
            if (thread==HandlerThread.MAIN) {
                assertEquals(0,handles.get()); assertNotNull(valid.queued); valid.queued.run();
            } else assertNull(valid.queued);
            assertEquals(3,handles.get());
        }
    }
}
