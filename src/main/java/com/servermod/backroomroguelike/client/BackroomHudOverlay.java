package com.servermod.backroomroguelike.client;

import com.servermod.backroomroguelike.BackroomRoguelike;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BackroomRoguelike.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BackroomHudOverlay {
    private static volatile long remainingMillis = -1L;
    private static volatile long receivedAtNanos;

    private BackroomHudOverlay() {}

    public static void setRemaining(long value) { remainingMillis = value; receivedAtNanos = System.nanoTime(); }

    @SubscribeEvent
    public static void register(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("backroom_timer", (gui, graphics, partialTick, width, height) -> render(graphics, width));
    }

    private static void render(GuiGraphics graphics, int width) {
        Minecraft minecraft = Minecraft.getInstance();
        if (remainingMillis < 0 || minecraft.player == null || minecraft.level == null
                || !minecraft.level.dimension().location().equals(new net.minecraft.resources.ResourceLocation(BackroomRoguelike.MOD_ID, "backroom"))) return;
        long remaining = Math.max(0L, remainingMillis - (System.nanoTime() - receivedAtNanos) / 1_000_000L);
        long minutes = remaining / 60_000L;
        long seconds = (remaining / 1_000L) % 60L;
        long millis = remaining % 1_000L;
        String text = "%02d:%02d.%03d".formatted(minutes, seconds, millis);
        int x = (width - minecraft.font.width(text)) / 2;
        graphics.drawString(minecraft.font, text, x, 8, 0xFFFFFF, true);
    }
}
