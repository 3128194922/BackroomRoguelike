package com.servermod.backroomroguelike.session;

import com.servermod.backroomroguelike.BackroomRoguelike;
import com.servermod.backroomroguelike.network.BackroomNetwork;
import com.servermod.backroomroguelike.world.RoomPool;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMobGriefingEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = BackroomRoguelike.MOD_ID)
public final class BackroomRunManager {
    public static final ResourceKey<Level> BACKROOM = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
            new ResourceLocation(BackroomRoguelike.MOD_ID, "backroom"));
    public static final long RUN_MILLIS = 20L * 60L * 1000L;
    public static final long EXTRACTION_TICKS = 200L;
    private static final Map<UUID, Extraction> EXTRACTIONS = new HashMap<>();
    private static final Set<UUID> PENDING_ENTRIES = new HashSet<>();
    private record Extraction(BlockPos pos, long endsAt, int announcedSeconds, BlockPos returnFeet) {}

    private BackroomRunManager() {}

    public static boolean enter(ServerPlayer player) {
        if (player.level().dimension() == BACKROOM) return false;
        if (!RoomPool.isReady()) {
            player.sendSystemMessage(Component.literal("后室房间资源尚未成功加载，请检查数据包日志后重试。"));
            return false;
        }
        BackroomRunData data = BackroomRunData.get(player.serverLevel());
        UUID playerId = player.getUUID();
        if (data.get(playerId) != null || PENDING_ENTRIES.contains(playerId)) return false;
        ServerLevel target = player.getServer().getLevel(BACKROOM);
        if (target == null) {
            player.sendSystemMessage(Component.literal("后室维度未加载，无法进入。"));
            return false;
        }
        ServerLevel origin = player.serverLevel();
        BackroomRunData.Run run = data.start(playerId);
        int chunkX = run.originChunkX(), chunkZ = run.originChunkZ();
        PENDING_ENTRIES.add(playerId);
        player.sendSystemMessage(Component.literal("正在准备后室入口区块，请稍候。"));
        target.getChunkSource().getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true)
                .whenComplete((result, failure) -> player.getServer().execute(() -> {
                    PENDING_ENTRIES.remove(playerId);
                    if (data.get(playerId) == null) return;
                    if (failure != null || result == null || result.left().isEmpty()) {
                        data.finish(playerId);
                        if (!player.isRemoved() && player.getServer().getPlayerList().getPlayer(playerId) == player) {
                            player.sendSystemMessage(Component.literal("后室入口区块加载失败，请稍后重试。"));
                        }
                        return;
                    }
                    if (player.isRemoved() || player.getServer().getPlayerList().getPlayer(playerId) != player
                            || player.serverLevel() != origin) {
                        data.finish(playerId);
                        return;
                    }
                    ChunkAccess generatedChunk = result.left().orElseThrow();
                    int x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
                    int roomFloorY = target.getMinBuildHeight() + 1;
                    BlockPos safeFeet = null;
                    double nearest = Double.MAX_VALUE;
                    for (int localX = 0; localX < 16; localX++) for (int localZ = 0; localZ < 16; localZ++) {
                        BlockPos feet = new BlockPos(chunkX * 16 + localX, roomFloorY + 1, chunkZ * 16 + localZ);
                        if (!generatedChunk.getBlockState(feet).isAir() || !generatedChunk.getBlockState(feet.above()).isAir()
                                || !generatedChunk.getBlockState(feet.below()).isSolid()) continue;
                        double distance = feet.distSqr(new BlockPos(x, feet.getY(), z));
                        if (distance < nearest) { nearest = distance; safeFeet = feet; }
                    }
                    if (safeFeet == null) safeFeet = new BlockPos(x, roomFloorY + 1, z);
                    player.teleportTo(target, safeFeet.getX() + 0.5, safeFeet.getY(), safeFeet.getZ() + 0.5, player.getYRot(), player.getXRot());
                    if (!player.isCreative() && !player.isSpectator()) player.setGameMode(net.minecraft.world.level.GameType.ADVENTURE);
                    player.sendSystemMessage(Component.literal("本局开始，20 分钟后结算。"));
                    BackroomNetwork.sendTimer(player, RUN_MILLIS);
                }));
        return true;
    }

    public static void startExtraction(ServerPlayer player, BlockPos pos) {
        if (player.level().dimension() != BACKROOM || !hasActiveRun(player)) {
            player.sendSystemMessage(Component.literal("当前没有可结算的后室本局。"));
            return;
        }
        Extraction current = EXTRACTIONS.get(player.getUUID());
        if (current != null && current.pos().equals(pos)) return;
        Extraction extraction = new Extraction(pos.immutable(), player.level().getGameTime() + EXTRACTION_TICKS, 10, null);
        EXTRACTIONS.put(player.getUUID(), extraction);
        player.sendSystemMessage(Component.literal("撤离读条开始：保持在 4 格内并坚持 10 秒。"));
        player.displayClientMessage(Component.literal("撤离倒计时：10 秒"), true);
        playExtractionTick(player);
        preloadReturnLocation(player, extraction);
    }

    public static boolean hasActiveRun(ServerPlayer player) {
        return BackroomRunData.get(player.serverLevel()).get(player.getUUID()) != null;
    }

    @SubscribeEvent
    public static void playerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        BackroomRunData data = BackroomRunData.get(player.serverLevel());
        BackroomRunData.Run run = data.get(player.getUUID());
        if (player.level().dimension() == BACKROOM && run == null) {
            // Protect the dimension from untracked direct portal/command entries.
            return;
        }
        if (run != null && System.currentTimeMillis() - run.enteredAt() >= RUN_MILLIS) {
            settle(player, true, "本局时间已到，物品已掉落。", data);
            if (player.level().dimension() == BACKROOM) returnToOverworld(player);
            return;
        }
        if (run != null && player.tickCount % 20 == 0) {
            BackroomNetwork.sendTimer(player, Math.max(0L, RUN_MILLIS - (System.currentTimeMillis() - run.enteredAt())));
        }
        Extraction extraction = EXTRACTIONS.get(player.getUUID());
        if (extraction != null) {
            if (player.level().dimension() != BACKROOM || player.distanceToSqr(extraction.pos().getX() + 0.5, extraction.pos().getY() + 0.5, extraction.pos().getZ() + 0.5) > 16.0) {
                EXTRACTIONS.remove(player.getUUID());
                player.sendSystemMessage(Component.literal("撤离读条已中断。"));
                player.displayClientMessage(Component.literal("撤离读条已中断"), true);
            } else if (player.level().getGameTime() >= extraction.endsAt()) {
                if (extraction.returnFeet() == null) {
                    player.displayClientMessage(Component.literal("正在准备主世界撤离位置…"), true);
                } else {
                    EXTRACTIONS.remove(player.getUUID());
                    data.finish(player.getUUID());
                    BackroomNetwork.sendTimer(player, -1L);
                    player.displayClientMessage(Component.empty(), true);
                    returnToOverworld(player, extraction.returnFeet());
                    player.sendSystemMessage(Component.literal("撤离成功，物品已保留。"));
                }
            } else {
                long remainingTicks = extraction.endsAt() - player.level().getGameTime();
                int remainingSeconds = (int) ((remainingTicks + 19L) / 20L);
                if (remainingSeconds < extraction.announcedSeconds()) {
                    EXTRACTIONS.put(player.getUUID(), new Extraction(extraction.pos(), extraction.endsAt(), remainingSeconds, extraction.returnFeet()));
                    player.displayClientMessage(Component.literal("撤离倒计时：" + remainingSeconds + " 秒"), true);
                    playExtractionTick(player);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && EXTRACTIONS.remove(player.getUUID()) != null) {
            player.sendSystemMessage(Component.literal("受到伤害，撤离读条已中断。"));
            player.displayClientMessage(Component.literal("撤离读条已中断"), true);
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.level().dimension() == BACKROOM) {
            settle(player, true, "你在后室中死亡，背包已掉落。", BackroomRunData.get(player.serverLevel()));
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BackroomRunData data = BackroomRunData.get(player.serverLevel());
            BackroomRunData.Run run = data.get(player.getUUID());
            if (run != null && System.currentTimeMillis() - run.enteredAt() >= RUN_MILLIS) {
                settle(player, true, "离线期间本局时间已到，物品已掉落。", data);
                if (player.level().dimension() == BACKROOM) returnToOverworld(player);
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        EXTRACTIONS.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player && PENDING_ENTRIES.remove(player.getUUID())) {
            BackroomRunData.get(player.serverLevel()).finish(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !player.isCreative() && !player.isSpectator()) {
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer().level().dimension() == BACKROOM) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() != null && event.getEntity().level().dimension() == BACKROOM) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (event.getLevel().dimension() == BACKROOM) event.getAffectedBlocks().clear();
    }

    @SubscribeEvent
    public static void onMobGriefing(EntityMobGriefingEvent event) {
        if (event.getEntity().level().dimension() == BACKROOM) {
            event.setResult(net.minecraftforge.eventbus.api.Event.Result.DENY);
        }
    }

    @SubscribeEvent
    public static void onFluidSpread(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.getLevel() instanceof Level level && level.dimension() == BACKROOM) {
            event.setNewState(event.getOriginalState());
        }
    }

    @SubscribeEvent
    public static void onBucketUse(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().dimension() == BACKROOM && event.getItemStack().getItem() instanceof BucketItem) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        }
    }

    @SubscribeEvent
    public static void onFireIgnition(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().dimension() == BACKROOM
                && (event.getItemStack().is(net.minecraft.world.item.Items.FLINT_AND_STEEL)
                || event.getItemStack().is(net.minecraft.world.item.Items.FIRE_CHARGE))) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        }
    }

    private static void settle(ServerPlayer player, boolean drop, String message, BackroomRunData data) {
        if (data.get(player.getUUID()) == null) return;
        if (drop) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                var stack = player.getInventory().getItem(i);
                if (!stack.isEmpty()) {
                    ItemEntity item = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), stack.copy());
                    player.level().addFreshEntity(item);
                    player.getInventory().setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                }
            }
        }
        if (EXTRACTIONS.remove(player.getUUID()) != null) player.displayClientMessage(Component.empty(), true);
        PENDING_ENTRIES.remove(player.getUUID());
        data.finish(player.getUUID());
        BackroomNetwork.sendTimer(player, -1L);
        player.sendSystemMessage(Component.literal(message));
    }

    private static void preloadReturnLocation(ServerPlayer player, Extraction extraction) {
        ServerLevel overworld = player.getServer().overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        int chunkX = spawn.getX() >> 4;
        int chunkZ = spawn.getZ() >> 4;
        try {
            overworld.getChunkSource().getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true)
                    .whenComplete((result, failure) -> player.getServer().execute(() -> {
                        Extraction current = EXTRACTIONS.get(player.getUUID());
                        if (current == null || !current.pos().equals(extraction.pos()) || current.endsAt() != extraction.endsAt()) return;
                        if (failure != null || result == null || result.left().isEmpty()) {
                            EXTRACTIONS.remove(player.getUUID());
                            if (!player.isRemoved() && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player) {
                                player.sendSystemMessage(Component.literal("主世界撤离位置加载失败，撤离读条已中断，请重试。"));
                                player.displayClientMessage(Component.literal("撤离位置加载失败"), true);
                            }
                            return;
                        }
                        int surfaceY = overworld.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                spawn.getX(), spawn.getZ());
                        EXTRACTIONS.put(player.getUUID(), new Extraction(current.pos(), current.endsAt(),
                                current.announcedSeconds(), new BlockPos(spawn.getX(), surfaceY, spawn.getZ())));
                    }));
        } catch (RuntimeException exception) {
            EXTRACTIONS.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("主世界撤离位置加载失败，撤离读条已中断，请重试。"));
            player.displayClientMessage(Component.literal("撤离位置加载失败"), true);
        }
    }

    private static void playExtractionTick(ServerPlayer player) {
        player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.6F, 1.0F);
    }

    private static void returnToOverworld(ServerPlayer player, BlockPos destination) {
        ServerLevel overworld = player.getServer().overworld();
        player.teleportTo(overworld, destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5, player.getYRot(), player.getXRot());
        if (!player.isCreative() && !player.isSpectator()) player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
    }

    private static void returnToOverworld(ServerPlayer player) {
        ServerLevel overworld = player.getServer().overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        int surfaceY = overworld.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                spawn.getX(), spawn.getZ());
        returnToOverworld(player, new BlockPos(spawn.getX(), surfaceY, spawn.getZ()));
    }
}
