package org.xiyu.reforged.mixin;

import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.xiyu.reforged.bridge.NeoAttachmentHolderBridge;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Adds NeoForge's attachment data methods to {@link Entity}.
 *
 * <p>NeoForge mods (e.g. Champions) call {@code entity.getData(Supplier)}
 * to access per-entity data attachments. Forge's Entity doesn't have these
 * methods. This mixin delegates to the persistent NeoForge attachment holder.</p>
 */
@Mixin(Entity.class)
public class EntityAttachmentMixin implements IAttachmentHolder, NeoAttachmentHolderBridge {

    @Unique
    private final AttachmentHolder.AsField reforged$neoAttachmentHolder = new AttachmentHolder.AsField(this);

    @Override
    public boolean hasAttachments() {
        return reforged$neoAttachmentHolder.hasAttachments();
    }

    @Override
    public <T> T getData(AttachmentType<T> type) {
        return reforged$neoAttachmentHolder.getData(type);
    }

    @Override
    public boolean hasData(AttachmentType<?> type) {
        return reforged$neoAttachmentHolder.hasData(type);
    }

    @Override
    public <T> T setData(AttachmentType<T> type, T value) {
        return reforged$neoAttachmentHolder.setData(type, value);
    }

    @Override
    public <T> T removeData(AttachmentType<T> type) {
        return reforged$neoAttachmentHolder.removeData(type);
    }

    // Supplier overloads
    public <T> T getData(Supplier<AttachmentType<T>> type) {
        return getData(type.get());
    }

    public <T> boolean hasData(Supplier<AttachmentType<T>> type) {
        return hasData(type.get());
    }

    public <T> T setData(Supplier<AttachmentType<T>> type, T value) {
        return setData(type.get(), value);
    }

    public <T> T removeData(Supplier<AttachmentType<T>> type) {
        return removeData(type.get());
    }

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

    @org.spongepowered.asm.mixin.injection.Inject(method = "saveWithoutId", at = @org.spongepowered.asm.mixin.injection.At("RETURN"), remap = false)
    private void reforged$saveAttachments(net.minecraft.nbt.CompoundTag tag,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.nbt.CompoundTag> ci) {
        var data = reforged$neoAttachmentHolder.serializeAttachments(((Entity) (Object) this).registryAccess());
        if (data != null) tag.put(AttachmentHolder.ATTACHMENTS_NBT_KEY, data);
        else tag.remove(AttachmentHolder.ATTACHMENTS_NBT_KEY);
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "load", at = @org.spongepowered.asm.mixin.injection.At("RETURN"), remap = false)
    private void reforged$loadAttachments(net.minecraft.nbt.CompoundTag tag,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        reforged$neoAttachmentHolder.deserializeAttachments(((Entity) (Object) this).registryAccess(),
                tag.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY));
    }

    @Override
    public AttachmentHolder.AsField reforged$getNeoAttachmentHolder() {
        return reforged$neoAttachmentHolder;
    }
}
