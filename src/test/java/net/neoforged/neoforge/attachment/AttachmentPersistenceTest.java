package net.neoforged.neoforge.attachment;

import com.mojang.serialization.Codec;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentPersistenceTest {
    final HolderLookup.Provider provider = HolderLookup.Provider.create(java.util.stream.Stream.empty());
    static AttachmentHolder holder() { return new AttachmentHolder() {}; }
    static <T> AttachmentType<T> registered(AttachmentType<T> type) {
        AttachmentType.register("test:" + UUID.randomUUID().toString().replace("-",""),type); return type;
    }
    @Test void defaultQueryNeverCreatesAndBothOverloadsAgree() {
        AtomicInteger calls = new AtomicInteger();
        AttachmentType<Integer> type = AttachmentType.builder(() -> calls.incrementAndGet()).build();
        AttachmentHolder h = holder();
        assertNull(h.getExistingDataOrNull(type)); assertTrue(h.getExistingData(() -> type).isEmpty());
        assertEquals(0,calls.get()); h.setData(type,7);
        assertEquals(7,h.getExistingDataOrNull(() -> type)); h.removeData(type);
        assertNull(h.getExistingDataOrNull(type)); assertEquals(0,calls.get());
    }
    @Test void codecRoundTripAndUnknownKeysSurvive() {
        AttachmentType<Integer> type = registered(AttachmentType.builder(() -> 0).serialize(Codec.INT).build());
        AttachmentHolder source=holder(), target=holder(); source.setData(type,27);
        CompoundTag encoded=source.serializeAttachments(provider); encoded.putString("missingmod:data","keep");
        target.deserializeAttachments(provider,encoded);
        assertEquals(27,target.getData(type));
        assertEquals("keep",target.serializeAttachments(provider).getString("missingmod:data"));
        target.removeData(type);
        assertFalse(target.serializeAttachments(provider).contains(type.id()));
    }
    @Test void defaultCopyDoesNotAliasMutableData() {
        AttachmentType<List<Integer>> type=registered(AttachmentType.<List<Integer>>builder(() -> new ArrayList<>())
                .serialize(Codec.INT.listOf().xmap(ArrayList::new,List::copyOf)).build());
        AttachmentHolder source=holder(), target=holder(); source.getData(type).add(1);
        source.copyAttachmentsTo(provider,target,t -> true);
        assertEquals(List.of(1),target.getData(type)); assertNotSame(source.getData(type),target.getData(type));
        target.getData(type).add(2); assertEquals(List.of(1),source.getData(type));
    }
    @Test void deathCopyOnlyCopiesOptInSerializableValues() {
        AttachmentType<Integer> keep=registered(AttachmentType.builder(() -> 0).serialize(Codec.INT).copyOnDeath().build());
        AttachmentType<Integer> drop=registered(AttachmentType.builder(() -> 0).serialize(Codec.INT).build());
        AttachmentType<Integer> transientType=AttachmentType.builder(() -> 0).build();
        AttachmentHolder source=holder(),target=holder(); source.setData(keep,1); source.setData(drop,2); source.setData(transientType,3);
        source.copyAttachmentsTo(provider,target,AttachmentType::copyOnDeath);
        assertEquals(1,target.getExistingDataOrNull(keep)); assertFalse(target.hasData(drop)); assertFalse(target.hasData(transientType));
        assertThrows(IllegalStateException.class, () -> AttachmentType.builder(() -> 0).copyOnDeath());
    }
    @Test void failedDecodeDoesNotCommitPartialState() {
        AttachmentType<Integer> type=registered(AttachmentType.builder(() -> 0).serialize(Codec.INT).build());
        AttachmentHolder target=holder(); target.setData(type,8);
        CompoundTag malformed=new CompoundTag(); malformed.putString(type.id(),"invalid");
        assertThrows(RuntimeException.class, () -> target.deserializeAttachments(provider,malformed));
        assertEquals(8,target.getData(type));
    }
    @Test void facadeFactoryReceivesRealHolderAndUpdatesSyncHook() {
        AtomicInteger syncs=new AtomicInteger();
        IAttachmentHolder exposed=new AttachmentHolder() { public void syncData(AttachmentType<?> type) { syncs.incrementAndGet(); } };
        AttachmentHolder field=new AttachmentHolder.AsField(exposed);
        AttachmentType<IAttachmentHolder> type=AttachmentType.builder(h -> h).build();
        assertSame(exposed,field.getData(type)); assertEquals(1,syncs.get());
        field.setData(type,exposed); field.removeData(type); assertEquals(3,syncs.get());
    }

    @Test void failedCopyDoesNotCommitEarlierValues() {
        AtomicInteger copies = new AtomicInteger();
        IAttachmentCopyHandler<Integer> handler = (value, target, lookup) -> {
            if (copies.incrementAndGet() == 2) throw new IllegalStateException("second copy failed");
            return value;
        };
        AttachmentType<Integer> first = registered(AttachmentType.builder(() -> 0).serialize(Codec.INT).copyHandler(handler).build());
        AttachmentType<Integer> second = registered(AttachmentType.builder(() -> 0).serialize(Codec.INT).copyHandler(handler).build());
        AttachmentHolder source = holder(), target = holder();
        source.setData(first, 1); source.setData(second, 2);
        target.setData(first, 10); target.setData(second, 20);
        assertThrows(IllegalStateException.class, () -> source.copyAttachmentsTo(provider, target, type -> true));
        assertEquals(2, copies.get());
        assertEquals(10, target.getExistingDataOrNull(first));
        assertEquals(20, target.getExistingDataOrNull(second));
    }

    @Test void successfulCopyPreservesUnselectedTargetValues() {
        AttachmentType<Integer> copied = registered(AttachmentType.builder(() -> 0).serialize(Codec.INT).build());
        AttachmentType<Integer> retained = registered(AttachmentType.builder(() -> 0).serialize(Codec.INT).build());
        AttachmentHolder source = holder(), target = holder();
        source.setData(copied, 7); source.setData(retained, 8);
        target.setData(retained, 80);
        source.copyAttachmentsTo(provider, target, type -> type == copied);
        assertEquals(7, target.getExistingDataOrNull(copied));
        assertEquals(80, target.getExistingDataOrNull(retained));
    }
}
