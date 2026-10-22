package com.servermod.backroomroguelike.world;

import com.servermod.backroomroguelike.BackroomRoguelike;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mod.EventBusSubscriber(modid = BackroomRoguelike.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RoomPoolReloadListener {
    private RoomPoolReloadListener() {}

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new PreparableReloadListener() {
            @Override
            public CompletableFuture<Void> reload(PreparationBarrier barrier, net.minecraft.server.packs.resources.ResourceManager manager,
                                                  ProfilerFiller preparationProfiler, ProfilerFiller reloadProfiler,
                                                  Executor backgroundExecutor, Executor gameExecutor) {
                return CompletableFuture.runAsync(() -> RoomPool.reload(manager), backgroundExecutor)
                        .thenCompose(barrier::wait)
                        .thenAcceptAsync(ignored -> {}, gameExecutor);
            }
        });
    }
}
