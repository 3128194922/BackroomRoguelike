package com.servermod.backroomroguelike.api;

import com.servermod.backroomroguelike.BackroomRoguelike;
import com.servermod.backroomroguelike.world.RoomPool;
import com.servermod.backroomroguelike.world.RoomTemplate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.function.Consumer;

/** Locates room templates from the generator's coordinate selector without loading their chunks. */
@Mod.EventBusSubscriber(modid = BackroomRoguelike.MOD_ID)
public final class RoomLocator {
    private static final Queue<Search> SEARCHES = new ArrayDeque<>();
    private static final int CHUNKS_PER_TICK = 512;

    private RoomLocator() {}

    public static void findNearest(ServerLevel level, BlockPos origin, ResourceLocation roomId, int maxChunkRadius,
                                   Consumer<Optional<RoomLocation>> callback) {
        if (maxChunkRadius < 0) throw new IllegalArgumentException("maxChunkRadius must be nonnegative");
        if (!RoomPool.isReady()) {
            callback.accept(Optional.empty());
            return;
        }
        SEARCHES.add(new Search(level, origin.immutable(), roomId, maxChunkRadius, callback));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SEARCHES.isEmpty()) return;
        int budget = CHUNKS_PER_TICK;
        int tasks = SEARCHES.size();
        while (budget > 0 && tasks-- > 0 && !SEARCHES.isEmpty()) {
            Search search = SEARCHES.remove();
            int consumed = search.step(Math.min(budget, CHUNKS_PER_TICK));
            budget -= consumed;
            if (!search.finished()) SEARCHES.add(search);
        }
    }

    private static final class Search {
        private final ServerLevel level;
        private final BlockPos origin;
        private final ResourceLocation roomId;
        private final int radius;
        private final Consumer<Optional<RoomLocation>> callback;
        private final int centerX, centerZ, width;
        private final long total;
        private long cursor;
        private RoomLocation best;

        private Search(ServerLevel level, BlockPos origin, ResourceLocation roomId, int radius, Consumer<Optional<RoomLocation>> callback) {
            this.level = level; this.origin = origin; this.roomId = roomId; this.radius = radius; this.callback = callback;
            this.centerX = origin.getX() >> 4; this.centerZ = origin.getZ() >> 4;
            this.width = radius * 2 + 1; this.total = (long) width * width;
        }

        private int step(int limit) {
            int used = 0;
            while (used < limit && cursor < total) {
                int dx = (int) (cursor % width) - radius;
                int dz = (int) (cursor / width) - radius;
                cursor++; used++;
                RoomTemplate selected = RoomPool.select(centerX + dx, centerZ + dz);
                if (!selected.id().equals(roomId)) continue;
                int blockX = (centerX + dx) * 16 + 8, blockZ = (centerZ + dz) * 16 + 8;
                double distance = origin.distToCenterSqr(blockX, origin.getY(), blockZ);
                if (best == null || distance < best.distanceSquared()) {
                    best = new RoomLocation(roomId, new ChunkPos(centerX + dx, centerZ + dz), new BlockPos(blockX, origin.getY(), blockZ), distance);
                }
            }
            if (cursor >= total) callback.accept(Optional.ofNullable(best));
            return used;
        }

        private boolean finished() { return cursor >= total; }
    }
}
