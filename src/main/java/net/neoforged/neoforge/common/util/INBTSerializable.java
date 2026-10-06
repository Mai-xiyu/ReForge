package net.neoforged.neoforge.common.util;

import net.minecraft.nbt.Tag;

/** Proxy: NeoForge's INBTSerializable */
public interface INBTSerializable<T extends Tag> {
    default T serializeNBT() { throw new UnsupportedOperationException("A serializer implementation is required"); }
    default void deserializeNBT(T nbt) { throw new UnsupportedOperationException("A deserializer implementation is required"); }
    default T serializeNBT(net.minecraft.core.HolderLookup.Provider provider) { return serializeNBT(); }
    default void deserializeNBT(net.minecraft.core.HolderLookup.Provider provider, T nbt) { deserializeNBT(nbt); }
}
