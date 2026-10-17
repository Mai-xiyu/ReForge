package org.xiyu.reforged.shim.attachment;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * AttachmentHolder — Actual storage implementation for NeoForge's Data Attachment system.
 *
 * <p>This class holds an {@link IdentityHashMap} that maps {@link AttachmentType} to data values.
 * It replaces the previous global static map approach (which had identity hash collision and
 * memory leak issues).</p>
 *
 * <p>This class can be used in two ways:
 * <ol>
 *   <li>Extended by classes that need attachment support (rare in bridge context)</li>
 *   <li>Stored as a field via {@link AsField} in objects that cannot extend this class
 *       (the primary pattern — we inject this field via Mixin into Entity, LevelChunk, Level)</li>
 * </ol>
 */
public class AttachmentHolder implements IAttachmentHolder {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The actual attachment data storage, lazily initialized. */
    private Map<AttachmentType<?>, Object> attachments = null;
    /** Unknown serialized keys are retained so temporarily missing mods do not lose data. */
    private CompoundTag unresolvedAttachments = new CompoundTag();

    /**
     * Creates the attachment map if it does not yet exist.
     */
    final Map<AttachmentType<?>, Object> getAttachmentMap() {
        if (attachments == null) {
            attachments = new IdentityHashMap<>(4);
        }
        return attachments;
    }

    /**
     * Returns the holder that should be exposed to default value factories.
     * Override in {@link AsField} to return the actual holder (e.g., Entity).
     */
    IAttachmentHolder getExposedHolder() {
        return this;
    }

    @Override
    public boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }

    @Override
    public <T> boolean hasData(AttachmentType<T> type) {
        Objects.requireNonNull(type);
        return attachments != null && attachments.containsKey(type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getData(AttachmentType<T> type) {
        Objects.requireNonNull(type);
        T ret = (T) getAttachmentMap().get(type);
        if (ret == null) {
            ret = type.createDefaultValue(getExposedHolder());
            if (ret != null) {
                attachments.put(type, ret);
                syncData(type);
            }
        }
        return ret;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T setData(AttachmentType<T> type, T value) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(value);
        T previous = (T) getAttachmentMap().put(type, value);
        syncData(type);
        return previous;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T removeData(AttachmentType<T> type) {
        Objects.requireNonNull(type);
        if (attachments == null) {
            return null;
        }
        T previous = (T) attachments.remove(type);
        syncData(type);
        return previous;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getExistingData(AttachmentType<T> type) {
        return Optional.ofNullable(getExistingDataOrNull(type));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getExistingDataOrNull(AttachmentType<T> type) {
        Objects.requireNonNull(type);
        return attachments == null ? null : (T) attachments.get(type);
    }

    /**
     * Serializes all serializable attachments to NBT.
     * Uses each AttachmentType's Codec to encode values.
     *
     * <p>Encoding failures are propagated. Silently dropping a known attachment
     * would make a successful save destructive and impossible to diagnose.</p>
     */
    @SuppressWarnings("unchecked")
    public CompoundTag serializeAttachments(HolderLookup.Provider provider) {
        CompoundTag tag = unresolvedAttachments.copy();
        if (attachments == null || attachments.isEmpty()) {
            return tag.isEmpty() ? null : tag;
        }
        for (var entry : attachments.entrySet()) {
            AttachmentType<?> type = entry.getKey();
            if (!type.shouldSerialize() || type.id() == null || type.codec() == null) {
                continue;
            }
            try {
                com.mojang.serialization.Codec<Object> codec = (com.mojang.serialization.Codec<Object>) type.codec();
                var ops = provider.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
                var encoded = codec.encodeStart(ops, entry.getValue()).getOrThrow();
                tag.put(type.id(), encoded);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Failed to serialize attachment '" + type.id() + "'", failure);
            }
        }
        return tag.isEmpty() ? null : tag;
    }

    /**
     * Restores attachments transactionally. Unknown keys remain attached to the
     * holder and are emitted again on the next save.
     */
    @SuppressWarnings("unchecked")
    public void deserializeAttachments(HolderLookup.Provider provider, CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return;
        }
        Map<AttachmentType<?>, Object> restored = new IdentityHashMap<>();
        CompoundTag unresolved = new CompoundTag();
        for (String key : tag.getAllKeys()) {
            AttachmentType<?> type = AttachmentTypeRegistry.getById(key);
            if (type == null || !type.shouldSerialize() || type.codec() == null) {
                var unknown = tag.get(key);
                if (unknown != null) {
                    unresolved.put(key, unknown.copy());
                }
                continue;
            }
            try {
                var ops = provider.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
                var codec = (com.mojang.serialization.Codec<Object>) type.codec();
                Object value = codec.parse(ops, tag.get(key)).getOrThrow();
                restored.put(type, Objects.requireNonNull(value, "Deserialized attachment " + key));
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Failed to deserialize attachment '" + key + "'", failure);
            }
        }
        attachments = restored.isEmpty() ? null : restored;
        unresolvedAttachments = unresolved;
    }

    /**
     * Copies selected serializable values into another holder without partially
     * committing results when a later copy handler fails.
     */
    @SuppressWarnings("unchecked")
    public void copyAttachmentsTo(HolderLookup.Provider provider, AttachmentHolder target,
                                  Predicate<AttachmentType<?>> filter) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(filter, "filter");
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        Map<AttachmentType<?>, Object> copies = new IdentityHashMap<>();
        for (var entry : attachments.entrySet()) {
            AttachmentType<?> type = entry.getKey();
            if (!type.shouldSerialize() || !filter.test(type)) {
                continue;
            }
            Object copy = entry.getValue();
            var handler = (IAttachmentCopyHandler<Object>) type.copyHandler();
            if (handler != null) {
                copy = handler.copy(copy, target.getExposedHolder(), provider);
            } else {
                var codec = (com.mojang.serialization.Codec<Object>) type.codec();
                var ops = provider.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
                var encoded = codec.encodeStart(ops, copy).getOrThrow();
                copy = codec.parse(ops, encoded.copy()).getOrThrow();
            }
            if (copy != null) {
                copies.put(type, copy);
            }
        }
        if (!copies.isEmpty()) {
            target.getAttachmentMap().putAll(copies);
        }
    }

    /**
     * Version of {@link AttachmentHolder} that can be stored as a field.
     * This is used when the class cannot extend AttachmentHolder directly.
     */
    public static class AsField extends AttachmentHolder {
        private final IAttachmentHolder exposedHolder;

        public AsField(IAttachmentHolder exposedHolder) {
            this.exposedHolder = exposedHolder;
        }

        @Override
        IAttachmentHolder getExposedHolder() {
            return exposedHolder;
        }

        @Override
        public void syncData(AttachmentType<?> type) {
            exposedHolder.syncData(type);
        }

        public void deserializeInternal(HolderLookup.Provider provider, CompoundTag tag) {
            deserializeAttachments(provider, tag);
        }
    }
}
