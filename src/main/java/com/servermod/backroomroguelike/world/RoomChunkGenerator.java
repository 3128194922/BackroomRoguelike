package com.servermod.backroomroguelike.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class RoomChunkGenerator extends ChunkGenerator {
    private static final int MIN_Y = -64;
    public static final MapCodec<RoomChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(BiomeSource.CODEC.fieldOf("biome_source").forGetter(RoomChunkGenerator::getBiomeSource))
            .apply(instance, RoomChunkGenerator::new));

    public RoomChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource, (Holder<Biome> biome) -> biome.value().getGenerationSettings());
    }

    @Override
    public Codec<? extends ChunkGenerator> codec() { return CODEC.codec(); }

    public static void register() {
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, new ResourceLocation("backroomroguelike", "room_generator"), CODEC.codec());
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState randomState,
                                                         StructureManager structureManager, ChunkAccess chunk) {
        int minY = chunk.getMinBuildHeight();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            chunk.setBlockState(cursor.set(chunk.getPos().getMinBlockX() + x, minY, chunk.getPos().getMinBlockZ() + z), Blocks.BEDROCK.defaultBlockState(), false);
        }
        int floorY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ());
        RoomTemplate room = RoomPool.select(chunk.getPos().x, chunk.getPos().z);
        placeTemplate(room, chunk, floorY, cursor);
        return CompletableFuture.completedFuture(chunk);
    }

    private void placeTemplate(RoomTemplate room, ChunkAccess chunk, int floorY, BlockPos.MutableBlockPos cursor) {
        CompoundTag root = room.nbt();
        var palette = room.palette();
        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        ChunkPos chunkPos = chunk.getPos();
        int[] size = room.size();
        for (int index = 0; index < blocks.size(); index++) {
            CompoundTag entry = blocks.getCompound(index);
            ListTag pos = entry.getList("pos", Tag.TAG_INT);
            int x = pos.getInt(0), y = pos.getInt(1), z = pos.getInt(2);
            if (x < 0 || x >= size[0] || z < 0 || z >= size[2]
                    || x >= 16 || z >= 16) continue;
            BlockState state = palette.get(entry.getInt("state"));
            BlockPos worldPos = cursor.set(chunkPos.getMinBlockX() + x, floorY + y, chunkPos.getMinBlockZ() + z).immutable();
            chunk.setBlockState(worldPos, state, false);
            if (entry.contains("nbt", Tag.TAG_COMPOUND)) {
                CompoundTag blockEntityTag = entry.getCompound("nbt").copy();
                blockEntityTag.putInt("x", worldPos.getX()); blockEntityTag.putInt("y", worldPos.getY()); blockEntityTag.putInt("z", worldPos.getZ());
                chunk.setBlockEntityNbt(blockEntityTag);
            }
        }
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {}

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState, net.minecraft.world.level.biome.BiomeManager biomeManager,
                             StructureManager structureManager, ChunkAccess chunk, net.minecraft.world.level.levelgen.GenerationStep.Carving carving) {}

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {}

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
        return level.getMinBuildHeight() + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        BlockState[] states = new BlockState[level.getHeight()];
        java.util.Arrays.fill(states, Blocks.AIR.defaultBlockState());
        states[0] = Blocks.BEDROCK.defaultBlockState();
        return new NoiseColumn(level.getMinBuildHeight(), states);
    }

    @Override
    public int getMinY() { return MIN_Y; }

    @Override
    public int getSeaLevel() { return MIN_Y + 1; }

    @Override
    public int getGenDepth() { return 384; }

    @Override
    public void addDebugScreenInfo(List<String> lines, RandomState randomState, BlockPos pos) {
        lines.add("Backroom room=" + RoomPool.select(pos.getX() >> 4, pos.getZ() >> 4).id());
    }
}
