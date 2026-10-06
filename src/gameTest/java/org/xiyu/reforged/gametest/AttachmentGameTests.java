package org.xiyu.reforged.gametest;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraftforge.gametest.GameTestHolder;
import net.neoforged.neoforge.attachment.*;
import org.xiyu.reforged.bridge.NeoAttachmentHolderBridge;

@GameTestHolder("reforged")
public final class AttachmentGameTests {
    private static final AttachmentType<Integer> DATA=AttachmentType.builder(() -> 0).serialize(Codec.INT).build();
    private static final AttachmentType<Integer> RESTART_DATA=AttachmentType.builder(() -> 0).serialize(Codec.INT).build();
    static {
        AttachmentType.register("reforged:gametest_data",DATA);
        AttachmentType.register("reforged:gametest_restart",RESTART_DATA);
    }

    @GameTest(template="forge:empty3x3x3")
    public static void entityRoundTrip(GameTestHelper helper) {
        var entity=helper.spawn(EntityType.PIG,new BlockPos(1,1,1));
        var holder=(IAttachmentHolder)entity;
        helper.assertTrue(holder.getExistingData(DATA).isEmpty(),"Query created data");
        holder.setData(DATA,42);
        CompoundTag saved=entity.saveWithoutId(new CompoundTag());
        holder.removeData(DATA); entity.load(saved);
        helper.assertTrue(holder.getData(DATA)==42,"Entity attachment did not survive save/load");
        helper.succeed();
    }

    @GameTest(template="forge:empty3x3x3")
    public static void blockEntityRoundTrip(GameTestHelper helper) {
        BlockPos pos=new BlockPos(1,1,1); helper.setBlock(pos,Blocks.CHEST);
        var block=helper.getBlockEntity(pos);
        var holder=(IAttachmentHolder)block; holder.setData(DATA,73);
        CompoundTag saved=block.saveWithoutMetadata(helper.getLevel().registryAccess());
        holder.removeData(DATA); block.loadWithComponents(saved,helper.getLevel().registryAccess());
        helper.assertTrue(holder.getData(DATA)==73,"Block entity attachment did not survive save/load");
        helper.succeed();
    }

    @GameTest(template="forge:empty3x3x3")
    public static void chunkRoundTrip(GameTestHelper helper) {
        var level=helper.getLevel(); var chunk=level.getChunkAt(helper.absolutePos(new BlockPos(1,1,1)));
        var holder=(IAttachmentHolder)chunk; holder.setData(DATA,91);
        CompoundTag saved=ChunkSerializer.write(level,chunk);
        var restored=ChunkSerializer.read(level,level.getPoiManager(),
                new RegionStorageInfo("reforged-test",level.dimension(),"chunk"),chunk.getPos(),saved);
        var actual=restored instanceof ImposterProtoChunk wrapper ? wrapper.getWrapped() : restored;
        helper.assertTrue(((IAttachmentHolder)actual).getData(DATA)==91,"Chunk attachment did not survive save/load");
        helper.succeed();
    }

    @GameTest(template="forge:empty3x3x3")
    public static void levelSavedData(GameTestHelper helper) {
        var level=helper.getLevel(); ((IAttachmentHolder)level).setData(DATA,113);
        var record=level.getDataStorage().get(new net.minecraft.world.level.saveddata.SavedData.Factory<
                org.xiyu.reforged.bridge.LevelAttachmentPersistence>(() -> null,(tag,provider) -> null,null),
                "reforged_attachments");
        helper.assertTrue(record!=null,"Level attachment saved data was not registered");
        helper.assertTrue(record.save(new CompoundTag(),level.registryAccess()).getInt(DATA.id())==113,
                "Level saved data does not share the level's attachment storage");
        helper.succeed();
    }

    @GameTest(template="forge:empty3x3x3")
    public static void levelSurvivesProcessRestart(GameTestHelper helper) {
        var holder=(IAttachmentHolder)helper.getLevel();
        String phase=System.getProperty("reforged.persistencePhase","write");
        if (phase.equals("write")) {
            holder.setData(RESTART_DATA,509);
            helper.getLevel().getDataStorage().save();
        } else if (phase.equals("read")) {
            helper.assertTrue(holder.getExistingData(RESTART_DATA).orElse(-1)==509,
                    "Level attachment did not survive process exit and world reload");
        } else {
            throw new IllegalArgumentException("Unknown persistence phase: " + phase);
        }
        helper.succeed();
    }

    @GameTest(template="forge:empty3x3x3", timeoutTicks=400)
    public static void worldObjectsSurviveProcessRestart(GameTestHelper helper) {
        var level=helper.getLevel();
        // Outside the structures cleared by the GameTest runner.
        BlockPos fixture=new BlockPos(256,64,256);
        level.setChunkForced(fixture.getX() >> 4,fixture.getZ() >> 4,false);
        level.setChunkForced(fixture.getX() >> 4,fixture.getZ() >> 4,true);
        var chunk=level.getChunkAt(fixture);
        String phase=System.getProperty("reforged.persistencePhase","write");
        if (phase.equals("write")) {
            level.setBlockAndUpdate(fixture.below(),Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(fixture,Blocks.CHEST.defaultBlockState());
            ((IAttachmentHolder)level.getBlockEntity(fixture)).setData(RESTART_DATA,601);
            ((IAttachmentHolder)chunk).setData(RESTART_DATA,602);
            var pig=EntityType.PIG.create(level);
            if (pig==null) throw new IllegalStateException("Could not create persistence fixture");
            pig.moveTo(256.5,66,256.5); pig.setNoGravity(true); pig.setNoAi(true);
            pig.setPersistenceRequired(); pig.addTag("reforged_persistence_fixture");
            ((IAttachmentHolder)pig).setData(RESTART_DATA,603);
            helper.assertTrue(level.addFreshEntity(pig),"Could not add persistence fixture");
            helper.succeed();
        } else if (phase.equals("read")) {
            helper.succeedWhen(() -> {
                var block=level.getBlockEntity(fixture);
                helper.assertTrue(block!=null,"Saved block entity missing after process restart");
                helper.assertTrue(((IAttachmentHolder)block).getExistingData(RESTART_DATA).orElse(-1)==601,
                        "Block entity attachment lost after process restart");
                helper.assertTrue(((IAttachmentHolder)chunk).getExistingData(RESTART_DATA).orElse(-1)==602,
                        "Chunk attachment lost after process restart");
                var entities=level.getEntities(EntityType.PIG,e -> e.getTags().contains("reforged_persistence_fixture"));
                helper.assertTrue(!entities.isEmpty(),"Saved entity missing after process restart");
                helper.assertTrue(entities.stream().allMatch(e -> ((IAttachmentHolder)e).getExistingData(RESTART_DATA).orElse(-1)==603),
                        "Entity attachment lost after process restart");
            });
        } else {
            throw new IllegalArgumentException("Unknown persistence phase: " + phase);
        }
    }

    @GameTest(template="forge:empty3x3x3")
    public static void neoModAndEmbeddedDependencyLoad(GameTestHelper helper) throws Exception {
        var loader=org.xiyu.reforged.core.NeoForgeModLoader.getNeoModClassLoader();
        helper.assertTrue(loader!=null,"NeoForge fixture loader was not created");
        var mod=loader.loadClass("fixture.MinimalNeoMod");
        helper.assertTrue(mod.getField("initialized").getBoolean(null),"NeoForge entrypoint was not constructed");
        helper.assertTrue(mod.getField("setupReceived").getBoolean(null),"NeoForge common setup listener was not called");
        helper.assertTrue(loader.loadClass("fixture.EmbeddedNeoMod").getField("initialized").getBoolean(null),
                "Embedded NeoForge dependency was not constructed");
        helper.succeed();
    }
}
