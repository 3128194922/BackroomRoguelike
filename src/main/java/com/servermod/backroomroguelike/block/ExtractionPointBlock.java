package com.servermod.backroomroguelike.block;

import com.servermod.backroomroguelike.registry.ModSounds;
import com.servermod.backroomroguelike.session.BackroomRunManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

public final class ExtractionPointBlock extends Block {
    public ExtractionPointBlock(BlockBehaviour.Properties properties) { super(properties); }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                 net.minecraft.world.phys.BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer && level.dimension() == BackroomRunManager.BACKROOM) {
            if (BackroomRunManager.startExtraction(serverPlayer, pos)) {
                level.playSound(null, pos, ModSounds.EXTRACTION_POINT_ACTIVATE.get(), SoundSource.BLOCKS, 0.9F, 1.0F);
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}
