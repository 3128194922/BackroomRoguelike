package com.servermod.backroomroguelike.registry;

import com.servermod.backroomroguelike.BackroomRoguelike;
import com.servermod.backroomroguelike.block.ExtractionPointBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, BackroomRoguelike.MOD_ID);

    public static final RegistryObject<Block> EXTRACTION_POINT = BLOCKS.register(
            "extraction_point",
            () -> new ExtractionPointBlock(BlockBehaviour.Properties.copy(Blocks.LODESTONE).strength(-1.0F, 3_600_000.0F))
    );

    private ModBlocks() {
    }
}
