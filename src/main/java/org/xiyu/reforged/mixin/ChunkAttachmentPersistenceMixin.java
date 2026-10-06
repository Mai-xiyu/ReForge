package org.xiyu.reforged.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.xiyu.reforged.bridge.NeoAttachmentHolderBridge;

@Mixin(ChunkSerializer.class)
public abstract class ChunkAttachmentPersistenceMixin {
    @Inject(method="write", at=@At("RETURN"), remap=false)
    private static void reforged$write(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<CompoundTag> ci) {
        if (chunk instanceof NeoAttachmentHolderBridge bridge) {
            CompoundTag tag=bridge.reforged$getNeoAttachmentHolder().serializeAttachments(level.registryAccess());
            if (tag != null) ci.getReturnValue().put(AttachmentHolder.ATTACHMENTS_NBT_KEY,tag);
        }
    }
    @Inject(method="read", at=@At("RETURN"), remap=false)
    private static void reforged$read(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos,
                                     CompoundTag tag, CallbackInfoReturnable<ProtoChunk> ci) {
        ChunkAccess result = ci.getReturnValue() instanceof net.minecraft.world.level.chunk.ImposterProtoChunk wrapper
                ? wrapper.getWrapped() : ci.getReturnValue();
        if (result instanceof NeoAttachmentHolderBridge bridge)
            bridge.reforged$getNeoAttachmentHolder().deserializeAttachments(level.registryAccess(),
                    tag.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY));
    }
}
