package com.servermod.backroomroguelike.item;

import com.servermod.backroomroguelike.api.RoomLocator;
import com.servermod.backroomroguelike.session.BackroomRunManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class RoomScannerItem extends Item {
    private static final ResourceLocation EXIT_ROOM = new ResourceLocation("backroomroguelike", "rooms/password/extraction_room");

    public RoomScannerItem(Properties properties) { super(properties); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer && level.dimension() == BackroomRunManager.BACKROOM) {
            if (!BackroomRunManager.hasActiveRun(serverPlayer)) {
                serverPlayer.sendSystemMessage(Component.literal("当前没有活动的后室本局，无法扫描撤离点。"));
                return InteractionResultHolder.sidedSuccess(stack, false);
            }
            boolean queued = RoomLocator.findNearest(serverPlayer.getUUID(), serverPlayer.serverLevel(),
                    serverPlayer.blockPosition(), EXIT_ROOM, 128, result -> {
                if (!serverPlayer.isAlive() || serverPlayer.connection == null) return;
                if (result.isEmpty()) serverPlayer.sendSystemMessage(Component.literal("扫描范围内没有预测到撤离点。"));
                else {
                    var found = result.get();
                    serverPlayer.sendSystemMessage(Component.literal("最近的撤离点预计坐标："
                            + found.origin().getX() + ", " + found.origin().getY() + ", " + found.origin().getZ()));
                }
            });
            if (queued) serverPlayer.sendSystemMessage(Component.literal("正在分段扫描 128 区块范围内的撤离点…"));
            else serverPlayer.sendSystemMessage(Component.literal("撤离点扫描已在进行中，请等待结果。"));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
