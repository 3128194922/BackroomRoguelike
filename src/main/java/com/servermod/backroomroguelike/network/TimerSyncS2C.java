package com.servermod.backroomroguelike.network;

import com.servermod.backroomroguelike.client.BackroomHudOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record TimerSyncS2C(long remainingMillis) {
    public static void encode(TimerSyncS2C message, FriendlyByteBuf buffer) { buffer.writeLong(message.remainingMillis()); }
    public static TimerSyncS2C decode(FriendlyByteBuf buffer) { return new TimerSyncS2C(buffer.readLong()); }
    public static void handle(TimerSyncS2C message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> BackroomHudOverlay.setRemaining(message.remainingMillis())));
        context.get().setPacketHandled(true);
    }
}
