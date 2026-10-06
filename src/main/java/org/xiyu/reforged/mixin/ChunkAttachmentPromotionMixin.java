package org.xiyu.reforged.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.neoforged.neoforge.attachment.AttachmentInternals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.xiyu.reforged.bridge.NeoAttachmentHolderBridge;

@Mixin(LevelChunk.class)
public abstract class ChunkAttachmentPromotionMixin {
    @Inject(method="<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V", at=@At("RETURN"), remap=false)
    private void reforged$promote(ServerLevel level, ProtoChunk source, LevelChunk.PostLoadProcessor processor, CallbackInfo ci) {
        if (source instanceof NeoAttachmentHolderBridge from && (Object)this instanceof NeoAttachmentHolderBridge to)
            to.reforged$getNeoAttachmentHolder().deserializeAttachments(level.registryAccess(),
                    java.util.Objects.requireNonNullElseGet(from.reforged$getNeoAttachmentHolder().serializeAttachments(level.registryAccess()),
                            net.minecraft.nbt.CompoundTag::new));
    }
}
