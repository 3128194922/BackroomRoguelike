package com.servermod.backroomroguelike.item;

import com.servermod.backroomroguelike.api.RoomLocator;
import com.servermod.backroomroguelike.registry.ModSounds;
import com.servermod.backroomroguelike.session.BackroomRunManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class RadioCallerItem extends Item {
    private static final ResourceLocation EXIT_ROOM =
            new ResourceLocation("backroomroguelike", "rooms/password/extraction_room");

    public RadioCallerItem(Properties properties) { super(properties); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer && level.dimension() == BackroomRunManager.BACKROOM) {
            if (!BackroomRunManager.hasActiveRun(serverPlayer)) {
                serverPlayer.sendSystemMessage(Component.literal("当前没有活动的后室本局，无法呼叫撤离电台。"));
                return InteractionResultHolder.sidedSuccess(stack, false);
            }
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    ModSounds.EXTRACTION_POINT_ACTIVATE.get(), SoundSource.PLAYERS, 0.7F, 1.2F);
            boolean queued = RoomLocator.findNearest(serverPlayer.getUUID(), serverPlayer.serverLevel(),
                    serverPlayer.blockPosition(), EXIT_ROOM, 128, result -> {
                if (!serverPlayer.isAlive() || serverPlayer.connection == null) return;
                if (result.isEmpty()) serverPlayer.sendSystemMessage(Component.literal("撤离电台没有回应，附近可能没有撤离电台。"));
                else {
                    var found = result.get();
                    serverPlayer.sendSystemMessage(Component.literal("撤离电台回应：最近撤离电台预计坐标："
                            + found.origin().getX() + ", " + found.origin().getY() + ", " + found.origin().getZ()));
                }
            });
            if (queued) serverPlayer.sendSystemMessage(Component.literal("正在呼叫撤离电台，分段扫描 128 区块范围…"));
            else serverPlayer.sendSystemMessage(Component.literal("撤离电台呼叫已在进行中，请等待回应。"));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("右键：呼叫撤离电台，获取最近撤离电台坐标").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("仅在后室维度有效").withStyle(ChatFormatting.DARK_GRAY));
    }
}
