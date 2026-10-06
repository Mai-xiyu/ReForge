package net.neoforged.neoforge.attachment;

import com.mojang.serialization.Codec;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;

/** NeoForge attachment API facade. Serializable attachments retain their copy/persistence contract. */
public final class AttachmentType<T> {
    private static final Map<String, AttachmentType<?>> TYPES = new ConcurrentHashMap<>();
    final Function<IAttachmentHolder,T> defaultValueSupplier;
    final IAttachmentSerializer<?,T> serializer;
    final boolean copyOnDeath;
    final IAttachmentCopyHandler<T> copyHandler;
    private String id;

    private AttachmentType(Builder<T> builder) {
        defaultValueSupplier = builder.defaultValueSupplier;
        serializer = builder.serializer;
        copyOnDeath = builder.copyOnDeath;
        copyHandler = builder.copyHandler != null ? builder.copyHandler : this::copySerialized;
    }
    @SuppressWarnings("unchecked")
    private T copySerialized(T data, IAttachmentHolder target, HolderLookup.Provider provider) {
        if (serializer == null) throw new UnsupportedOperationException("Cannot copy non-serializable attachments");
        var typed = (IAttachmentSerializer<Tag,T>) serializer;
        Tag encoded = typed.write(data, provider);
        return encoded == null ? null : typed.read(target, encoded.copy(), provider);
    }
    public static synchronized void register(String id, AttachmentType<?> type) {
        Objects.requireNonNull(id); Objects.requireNonNull(type);
        if (type.id != null && !type.id.equals(id)) throw new IllegalStateException("Attachment type already registered as " + type.id);
        AttachmentType<?> existing = TYPES.putIfAbsent(id, type);
        if (existing != null && existing != type) throw new IllegalStateException("Duplicate attachment id " + id);
        type.id = id;
    }
    static AttachmentType<?> byId(String id) { return TYPES.get(id); }
    public String id() { return id; }
    public Supplier<T> defaultValueSupplier() { return () -> defaultValueSupplier.apply(null); }
    public T createDefaultValue(IAttachmentHolder holder) { return Objects.requireNonNull(defaultValueSupplier.apply(holder), "Attachment default value"); }
    public boolean copyOnDeath() { return copyOnDeath; }
    public static <T> Builder<T> builder(Supplier<T> supplier) { return builder(holder -> supplier.get()); }
    public static <T> Builder<T> builder(Function<IAttachmentHolder,T> factory) { return new Builder<>(factory); }
    public static <S extends Tag,T extends INBTSerializable<S>> Builder<T> serializable(Supplier<T> factory) {
        return serializable(holder -> factory.get());
    }
    public static <S extends Tag,T extends INBTSerializable<S>> Builder<T> serializable(Function<IAttachmentHolder,T> factory) {
        return builder(factory).serialize(new IAttachmentSerializer<S,T>() {
            public T read(IAttachmentHolder holder, S tag, HolderLookup.Provider provider) {
                T value = factory.apply(holder); value.deserializeNBT(provider, tag); return value;
            }
            public S write(T value, HolderLookup.Provider provider) { return value.serializeNBT(provider); }
        });
    }
    public static final class Builder<T> {
        private final Function<IAttachmentHolder,T> defaultValueSupplier;
        private IAttachmentSerializer<?,T> serializer;
        private boolean copyOnDeath;
        private IAttachmentCopyHandler<T> copyHandler;
        public Builder(Function<IAttachmentHolder,T> factory) { defaultValueSupplier = Objects.requireNonNull(factory); }
        public Builder<T> serialize(IAttachmentSerializer<?,T> serializer) {
            if (this.serializer != null) throw new IllegalStateException("Serializer already set");
            this.serializer = Objects.requireNonNull(serializer); return this;
        }
        public Builder<T> serialize(Codec<T> codec) { return serialize(codec, value -> true); }
        public Builder<T> serialize(Codec<T> codec, Predicate<? super T> predicate) {
            Objects.requireNonNull(codec); Objects.requireNonNull(predicate);
            return serialize(new IAttachmentSerializer<Tag,T>() {
                public T read(IAttachmentHolder holder, Tag tag, HolderLookup.Provider provider) {
                    return codec.parse(provider.createSerializationContext(NbtOps.INSTANCE), tag).getOrThrow();
                }
                public Tag write(T value, HolderLookup.Provider provider) {
                    return predicate.test(value) ? codec.encodeStart(provider.createSerializationContext(NbtOps.INSTANCE),value).getOrThrow() : null;
                }
            });
        }
        @SuppressWarnings("unchecked")
        public Builder<T> serialize(Object value) {
            if (value instanceof Codec<?> codec) return serialize((Codec<T>)codec);
            if (value instanceof IAttachmentSerializer<?,?> serializer) return serialize((IAttachmentSerializer<?,T>)serializer);
            throw new IllegalArgumentException("Unsupported attachment serializer " + value);
        }
        public Builder<T> copyOnDeath() { requireSerializer(); copyOnDeath=true; return this; }
        public Builder<T> copyHandler(IAttachmentCopyHandler<T> handler) { requireSerializer(); copyHandler=Objects.requireNonNull(handler); return this; }
        @SuppressWarnings("unchecked")
        public Builder<T> copyHandler(Object handler) { return copyHandler((IAttachmentCopyHandler<T>)handler); }
        private void requireSerializer() { if (serializer == null) throw new IllegalStateException("Attachment copy requires serialization"); }
        public Builder<T> sync(AttachmentSyncHandler<T> handler) { throw new UnsupportedOperationException("Automatic attachment synchronization is not supported by ReForged"); }
        public Builder<T> sync(net.minecraft.network.codec.StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf,T> codec) {
            throw new UnsupportedOperationException("Automatic attachment synchronization is not supported by ReForged");
        }
        public Builder<T> sync(BiPredicate<IAttachmentHolder,net.minecraft.server.level.ServerPlayer> filter,
                               net.minecraft.network.codec.StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf,T> codec) {
            throw new UnsupportedOperationException("Automatic attachment synchronization is not supported by ReForged");
        }
        public Builder<T> sync(Object handler) { throw new UnsupportedOperationException("Automatic attachment synchronization is not supported by ReForged"); }
        public Builder<T> sync(BiPredicate<IAttachmentHolder,net.minecraft.server.level.ServerPlayer> filter,Object codec) {
            throw new UnsupportedOperationException("Automatic attachment synchronization is not supported by ReForged");
        }
        public AttachmentType<T> build() { return new AttachmentType<>(this); }
    }
}
