package com.servermod.backroomroguelike.network;

import com.servermod.backroomroguelike.BackroomRoguelike;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class BackroomNetwork {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(BackroomRoguelike.MOD_ID, "main"))
            .networkProtocolVersion(() -> VERSION)
            .clientAcceptedVersions(VERSION::equals)
            .serverAcceptedVersions(VERSION::equals)
            .simpleChannel();

    private BackroomNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(TimerSyncS2C.class, 0)
                .encoder(TimerSyncS2C::encode).decoder(TimerSyncS2C::decode)
                .consumerMainThread(TimerSyncS2C::handle).add();
    }

    public static void sendTimer(ServerPlayer player, long remainingMillis) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new TimerSyncS2C(remainingMillis));
    }
}
