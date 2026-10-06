package net.neoforged.neoforge.attachment;

import net.minecraft.core.HolderLookup;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public abstract class AttachmentHolder implements IAttachmentHolder {
    public static final String ATTACHMENTS_NBT_KEY = "neoforge:attachments";
    private net.minecraft.nbt.CompoundTag unresolvedAttachments = new net.minecraft.nbt.CompoundTag();
    @Nullable
    private Map<AttachmentType<?>, Object> attachments;

    final Map<AttachmentType<?>, Object> getAttachmentMap() {
        if (attachments == null) {
            attachments = new IdentityHashMap<>(4);
        }
        return attachments;
    }

    IAttachmentHolder getExposedHolder() {
        return this;
    }

    @Override
    public final boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }

    @Override
    public final boolean hasData(AttachmentType<?> type) {
        return attachments != null && attachments.containsKey(type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public final <T> T getData(AttachmentType<T> type) {
        T current = attachments == null ? null : (T) attachments.get(type);
        if (current == null) {
            current = type.createDefaultValue(getExposedHolder());
            if (current != null) {
                getAttachmentMap().put(type, current);
                syncData(type);
            }
        }
        return current;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getExistingDataOrNull(AttachmentType<T> type) {
        if (attachments == null) {
            return null;
        }
        return (T) attachments.get(type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T setData(AttachmentType<T> type, T data) {
        Objects.requireNonNull(data);
        T previous = (T) getAttachmentMap().put(type, data);
        syncData(type);
        return previous;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T removeData(AttachmentType<T> type) {
        if (attachments == null) {
            return null;
        }
        T previous = (T) attachments.remove(type);
        syncData(type);
        return previous;
    }

    @SuppressWarnings("unchecked")
    final void copyAttachmentsTo(HolderLookup.Provider provider, AttachmentHolder to, Predicate<AttachmentType<?>> filter) {
        if (attachments == null) {
            return;
        }

        Map<AttachmentType<?>, Object> copies = new IdentityHashMap<>();
        for (Map.Entry<AttachmentType<?>, Object> entry : attachments.entrySet()) {
            AttachmentType<?> type = entry.getKey();
            if (type.serializer == null || !filter.test(type)) {
                continue;
            }

            Object copy = entry.getValue();
            if (type.copyHandler != null) {
                copy = ((IAttachmentCopyHandler<Object>) type.copyHandler).copy(entry.getValue(), to.getExposedHolder(), provider);
            }

            if (copy != null) {
                copies.put(type, copy);
            }
        }
        // A later codec/copy-handler failure must not leave earlier values committed.
        if (!copies.isEmpty()) to.getAttachmentMap().putAll(copies);
    }

    /** Keep unknown keys across saves so temporarily missing mods do not destroy data. */
    @SuppressWarnings("unchecked")
    public final net.minecraft.nbt.CompoundTag serializeAttachments(HolderLookup.Provider provider) {
        var tag = unresolvedAttachments.copy();
        if (attachments != null) for (var entry : attachments.entrySet()) {
            AttachmentType<?> type = entry.getKey();
            if (type.serializer == null) continue;
            if (type.id() == null) throw new IllegalStateException("Unregistered serializable attachment");
            var serializer = (IAttachmentSerializer<net.minecraft.nbt.Tag,Object>) type.serializer;
            // Fail the save rather than silently drop a known attachment on codec failure.
            net.minecraft.nbt.Tag encoded = serializer.write(entry.getValue(), provider);
            if (encoded != null) tag.put(type.id(), encoded);
            else tag.remove(type.id());
        }
        return tag.isEmpty() ? null : tag;
    }

    @SuppressWarnings("unchecked")
    public final void deserializeAttachments(HolderLookup.Provider provider, net.minecraft.nbt.CompoundTag tag) {
        var restored = new IdentityHashMap<AttachmentType<?>, Object>();
        var unresolved = new net.minecraft.nbt.CompoundTag();
        for (String id : tag.getAllKeys()) {
            AttachmentType<?> type = AttachmentType.byId(id);
            if (type == null || type.serializer == null) {
                unresolved.put(id, Objects.requireNonNull(tag.get(id)).copy());
            } else {
                var serializer = (IAttachmentSerializer<net.minecraft.nbt.Tag,Object>) type.serializer;
                Object value = serializer.read(getExposedHolder(), tag.get(id), provider);
                restored.put(type, Objects.requireNonNull(value, "Deserialized attachment " + id));
            }
        }
        // Commit only after every known attachment has decoded successfully.
        attachments = restored;
        unresolvedAttachments = unresolved;
    }

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
    }
}
