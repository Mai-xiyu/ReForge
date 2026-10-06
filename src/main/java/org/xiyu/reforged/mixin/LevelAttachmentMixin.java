package org.xiyu.reforged.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.common.extensions.ILevelExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.xiyu.reforged.bridge.ServerLevelCapabilityBridge;
import org.xiyu.reforged.shim.attachment.AttachmentHolder;
import org.xiyu.reforged.shim.attachment.AttachmentType;
import org.xiyu.reforged.shim.attachment.IAttachmentHolder;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Injects {@link AttachmentHolder.AsField} into Level (in-memory only, not serialized).
 * Also implements {@link ILevelExtension} so that NeoForge's
 * {@code Level.getCapability(BlockCapability, ...)} default methods are available.
 */
@Mixin(Level.class)
public abstract class LevelAttachmentMixin implements ILevelExtension {

    @Nullable
    public <T, C> T getCapability(BlockCapability<T, C> cap, BlockPos pos, @Nullable C context) {
        return cap.getCapability((Level) (Object) this, pos, null, null, context);
    }

    @Nullable
    public <T, C> T getCapability(BlockCapability<T, C> cap, BlockPos pos,
                                  @Nullable BlockState state, @Nullable BlockEntity blockEntity,
                                  @Nullable C context) {
        return cap.getCapability((Level) (Object) this, pos, state, blockEntity, context);
    }

    @Nullable
    public <T> T getCapability(BlockCapability<T, Void> cap, BlockPos pos) {
        return cap.getCapability((Level) (Object) this, pos, null, null, null);
    }

    @Nullable
    public <T> T getCapability(BlockCapability<T, Void> cap, BlockPos pos,
                               @Nullable BlockState state, @Nullable BlockEntity blockEntity) {
        return cap.getCapability((Level) (Object) this, pos, state, blockEntity, null);
    }

    public void invalidateCapabilities(BlockPos pos) {
        if ((Object) this instanceof ServerLevel && (Object) this instanceof ServerLevelCapabilityBridge bridge) {
            bridge.reforged$invalidateCapabilities(pos);
        }
    }

    public void invalidateCapabilities(ChunkPos pos) {
        if ((Object) this instanceof ServerLevel && (Object) this instanceof ServerLevelCapabilityBridge bridge) {
            bridge.reforged$invalidateCapabilities(pos);
        }
    }
}
