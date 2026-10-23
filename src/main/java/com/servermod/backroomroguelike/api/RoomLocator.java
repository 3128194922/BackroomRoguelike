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
import java.util.HashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Locates room templates from the generator's coordinate selector without loading their chunks. */
@Mod.EventBusSubscriber(modid = BackroomRoguelike.MOD_ID)
public final class RoomLocator {
    private static final Queue<Search> SEARCHES = new ArrayDeque<>();
    private static final Set<UUID> SEARCHING_PLAYERS = new HashSet<>();
    private static final int CHUNKS_PER_SEARCH_PER_TICK = 64;
    private static final int TOTAL_CHUNKS_PER_TICK = 256;

    private RoomLocator() {}

    public static void findNearest(ServerLevel level, BlockPos origin, ResourceLocation roomId, int maxChunkRadius,
                                   Consumer<Optional<RoomLocation>> callback) {
        findNearest(null, level, origin, roomId, maxChunkRadius, callback);
    }

    public static boolean findNearest(UUID requester, ServerLevel level, BlockPos origin, ResourceLocation roomId,
                                      int maxChunkRadius, Consumer<Optional<RoomLocation>> callback) {
        if (maxChunkRadius < 0) throw new IllegalArgumentException("maxChunkRadius must be nonnegative");
        if (!RoomPool.isReady()) {
            callback.accept(Optional.empty());
            return true;
        }
        if (requester != null && !SEARCHING_PLAYERS.add(requester)) return false;
        SEARCHES.add(new Search(requester, level, origin.immutable(), roomId, maxChunkRadius, callback));
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SEARCHES.isEmpty()) return;
        int budget = TOTAL_CHUNKS_PER_TICK;
        int tasks = SEARCHES.size();
        while (budget > 0 && tasks-- > 0 && !SEARCHES.isEmpty()) {
            Search search = SEARCHES.remove();
            int consumed = search.step(Math.min(budget, CHUNKS_PER_SEARCH_PER_TICK));
            budget -= consumed;
            if (!search.finished()) SEARCHES.add(search);
        }
    }

    private static final class Search {
        private final UUID requester;
        private final ServerLevel level;
        private final BlockPos origin;
        private final ResourceLocation roomId;
        private final int radius;
        private final Consumer<Optional<RoomLocation>> callback;
        private final int centerX, centerZ;
        private int ring;
        private int ringCursor;
        private boolean finished;
        private RoomLocation best;

        private Search(UUID requester, ServerLevel level, BlockPos origin, ResourceLocation roomId, int radius, Consumer<Optional<RoomLocation>> callback) {
            this.requester = requester; this.level = level; this.origin = origin; this.roomId = roomId; this.radius = radius; this.callback = callback;
            this.centerX = origin.getX() >> 4; this.centerZ = origin.getZ() >> 4;
        }

        private int step(int limit) {
            if (requester != null && level.getServer().getPlayerList().getPlayer(requester) == null) {
                finish();
                return 0;
            }
            int used = 0;
            while (used < limit && !finished) {
                int ringSize = ring == 0 ? 1 : 8 * ring;
                if (ringCursor >= ringSize) {
                    if (best != null && best.distanceSquared() <= nearestUnsearchedDistanceSquared(ring)) {
                        finish();
                        break;
                    }
                    if (ring >= radius) {
                        finish();
                        break;
                    }
                    ring++;
                    ringCursor = 0;
                    continue;
                }
                long offset = ringOffset(ring, ringCursor++);
                int dx = (int) (offset >> 32);
                int dz = (int) offset;
                used++;
                RoomTemplate selected = RoomPool.selectForChunk(centerX + dx, centerZ + dz);
                if (!selected.id().equals(roomId)) continue;
                int blockX = (centerX + dx) * 16 + 8, blockZ = (centerZ + dz) * 16 + 8;
                double distance = origin.distToCenterSqr(blockX, origin.getY(), blockZ);
                if (best == null || distance < best.distanceSquared()) {
                    int roomFeetY = level.getMinBuildHeight() + 2;
                    best = new RoomLocation(roomId, new ChunkPos(centerX + dx, centerZ + dz), new BlockPos(blockX, roomFeetY, blockZ), distance);
                }
            }
            return used;
        }

        private static long ringOffset(int ring, int index) {
            if (ring == 0) return 0L;
            int edge = 2 * ring + 1;
            int dx;
            int dz;
            if (index < edge) {
                dx = -ring + index;
                dz = -ring;
            } else if ((index -= edge) < 2 * ring) {
                dx = ring;
                dz = -ring + 1 + index;
            } else if ((index -= 2 * ring) < 2 * ring) {
                dx = ring - 1 - index;
                dz = ring;
            } else {
                index -= 2 * ring;
                dx = -ring;
                dz = ring - 1 - index;
            }
            return ((long) dx << 32) | (dz & 0xffffffffL);
        }

        private double nearestUnsearchedDistanceSquared(int completedRing) {
            double nearestAxisDistance = 16.0 * (completedRing + 1) - 9.0;
            return nearestAxisDistance * nearestAxisDistance;
        }

        private void finish() {
            if (finished) return;
            finished = true;
            if (requester != null) SEARCHING_PLAYERS.remove(requester);
            callback.accept(Optional.ofNullable(best));
        }

        private boolean finished() { return finished; }
    }
}
