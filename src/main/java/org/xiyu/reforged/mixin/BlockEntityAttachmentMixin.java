package org.xiyu.reforged.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.xiyu.reforged.bridge.NeoAttachmentHolderBridge;

import java.util.Optional;
import java.util.function.Supplier;

@Mixin(BlockEntity.class)
public class BlockEntityAttachmentMixin implements IAttachmentHolder, NeoAttachmentHolderBridge {

    @Unique
    private final AttachmentHolder.AsField reforged$neoAttachmentHolder = new AttachmentHolder.AsField(this);

    @Override
    public boolean hasAttachments() {
        return reforged$neoAttachmentHolder.hasAttachments();
    }

    @Override
    public boolean hasData(AttachmentType<?> type) {
        return reforged$neoAttachmentHolder.hasData(type);
    }

    @Override
    public <T> T getData(AttachmentType<T> type) {
        return reforged$neoAttachmentHolder.getData(type);
    }

    @Override
    public <T> T setData(AttachmentType<T> type, T data) {
        return reforged$neoAttachmentHolder.setData(type, data);
    }

    @Override
    public <T> T removeData(AttachmentType<T> type) {
        return reforged$neoAttachmentHolder.removeData(type);
    }

    @Override
    public <T> Optional<T> getExistingData(AttachmentType<T> type) {
        return reforged$neoAttachmentHolder.getExistingData(type);
    }

    @Override
    public <T> T getExistingDataOrNull(AttachmentType<T> type) {
        return reforged$neoAttachmentHolder.getExistingDataOrNull(type);
    }

    public <T> Optional<T> getExistingData(Supplier<AttachmentType<T>> type) {
        return getExistingData(type.get());
    }

    @Override
    public <T> T getExistingDataOrNull(Supplier<AttachmentType<T>> type) {
        return getExistingDataOrNull(type.get());
    }

    @Override
    public void syncData(AttachmentType<?> type) {
        ((BlockEntity) (Object) this).setChanged();
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = {"saveCustomOnly", "saveWithoutMetadata"}, at = @org.spongepowered.asm.mixin.injection.At("RETURN"), remap = false)
    private void reforged$saveAttachments(net.minecraft.core.HolderLookup.Provider provider,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.nbt.CompoundTag> ci) {
        var data = reforged$neoAttachmentHolder.serializeAttachments(provider);
        if (data != null) ci.getReturnValue().put(AttachmentHolder.ATTACHMENTS_NBT_KEY, data);
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = {"loadWithComponents", "loadCustomOnly"},
            at = @org.spongepowered.asm.mixin.injection.At("RETURN"), remap = false)
    private void reforged$loadAttachments(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        reforged$neoAttachmentHolder.deserializeAttachments(provider, tag.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY));
    }

    @Override
    public AttachmentHolder.AsField reforged$getNeoAttachmentHolder() {
        return reforged$neoAttachmentHolder;
    }
}
