package org.xiyu.reforged.shim.attachment;

import com.mojang.serialization.Codec;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentPersistenceContractTest {
    private static final HolderLookup.Provider PROVIDER =
            HolderLookup.Provider.create(java.util.stream.Stream.empty());

    private static <T> AttachmentType<T> register(String name,
                                                    Supplier<AttachmentType.Builder<T>> builder) {
        return AttachmentTypeRegistry.create("test").register(name, builder).get();
    }

    @Test
    void roundTripKeepsUnknownKeysAndDecodeIsTransactional() {
        String name = "shim_" + UUID.randomUUID().toString().replace("-", "");
        AttachmentType<Integer> registered = register(name, () -> AttachmentType.builder(() -> 0).serialize(Codec.INT));
        AttachmentHolder source = new AttachmentHolder();
        source.setData(registered, 27);
        CompoundTag encoded = source.serializeAttachments(PROVIDER);
        encoded.putString("missingmod:data", "keep");

        AttachmentHolder target = new AttachmentHolder();
        target.deserializeAttachments(PROVIDER, encoded);
        assertEquals(27, target.getData(registered));
        assertEquals("keep", target.serializeAttachments(PROVIDER).getString("missingmod:data"));

        CompoundTag malformed = new CompoundTag();
        malformed.putString(registered.id(), "invalid");
        target.setData(registered, 8);
        assertThrows(IllegalStateException.class, () -> target.deserializeAttachments(PROVIDER, malformed));
        assertEquals(8, target.getData(registered));
    }

    @Test
    void copyIsDeepAndFailureDoesNotPartiallyCommit() {
        String firstId = "test:copy_first_" + UUID.randomUUID().toString().replace("-", "");
        String secondId = "test:copy_second_" + UUID.randomUUID().toString().replace("-", "");
        AtomicInteger calls = new AtomicInteger();
        IAttachmentCopyHandler<Integer> handler = (value, target, provider) -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("second copy failed");
            return value;
        };
        AttachmentType<Integer> registeredFirst = register(firstId.substring(5),
                () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).copyHandler(handler));
        AttachmentType<Integer> registeredSecond = register(secondId.substring(5),
                () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).copyHandler(handler));
        AttachmentHolder source = new AttachmentHolder();
        AttachmentHolder target = new AttachmentHolder();
        source.setData(registeredFirst, 1);
        source.setData(registeredSecond, 2);
        target.setData(registeredFirst, 10);
        target.setData(registeredSecond, 20);
        assertThrows(IllegalStateException.class, () -> source.copyAttachmentsTo(PROVIDER, target, ignored -> true));
        assertEquals(10, target.getExistingDataOrNull(registeredFirst));
        assertEquals(20, target.getExistingDataOrNull(registeredSecond));
    }

    @Test
    void builderRejectsUnsupportedCopyAndSyncContracts() {
        assertThrows(IllegalStateException.class, () -> AttachmentType.builder(() -> 0).copyOnDeath());
        assertThrows(IllegalArgumentException.class, () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).copyHandler(new Object()));
        assertThrows(UnsupportedOperationException.class, () -> AttachmentType.builder(() -> 0).sync(new Object()));
        AttachmentType<Integer> type = AttachmentType.builder(() -> 0).serialize(Codec.INT).copyOnDeath().build();
        assertTrue(type.copyOnDeath());
    }

    @Test
    void defaultCodecCopyDoesNotAliasMutableValues() {
        AttachmentType<ArrayList<Integer>> type = AttachmentType.builder(() -> new ArrayList<Integer>())
                .serialize(Codec.INT.listOf().xmap(ArrayList::new, List::copyOf)).build();
        AttachmentHolder source = new AttachmentHolder();
        AttachmentHolder target = new AttachmentHolder();
        source.setData(type, new ArrayList<>(List.of(1)));
        source.copyAttachmentsTo(PROVIDER, target, ignored -> true);
        assertEquals(List.of(1), target.getData(type));
        assertNotSame(source.getData(type), target.getData(type));
        target.getData(type).add(2);
        assertEquals(List.of(1), source.getData(type));
    }
}
