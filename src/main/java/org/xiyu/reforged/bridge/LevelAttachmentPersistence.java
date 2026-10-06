package org.xiyu.reforged.bridge;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.level.LevelEvent;
import net.neoforged.neoforge.attachment.AttachmentHolder;

/** One saved-data record per server dimension; it shares that level's attachment holder. */
public final class LevelAttachmentPersistence extends SavedData {
    private final AttachmentHolder holder;
    private LevelAttachmentPersistence(AttachmentHolder holder) { this.holder=holder; }
    public static void onLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(level instanceof NeoAttachmentHolderBridge bridge)) return;
        AttachmentHolder holder=bridge.reforged$getNeoAttachmentHolder();
        level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(
                () -> new LevelAttachmentPersistence(holder),
                (tag,provider) -> {
                    holder.deserializeAttachments(provider,tag);
                    return new LevelAttachmentPersistence(holder);
                }, null), "reforged_attachments");
    }
    @Override public boolean isDirty() { return true; }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider provider) {
        CompoundTag serialized=holder.serializeAttachments(provider);
        if (serialized != null) tag.merge(serialized);
        return tag;
    }
}
