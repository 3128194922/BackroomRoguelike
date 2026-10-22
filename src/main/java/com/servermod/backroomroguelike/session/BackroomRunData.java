package com.servermod.backroomroguelike.session;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BackroomRunData extends SavedData {
    private static final String KEY = "backroomroguelike_runs";
    private long nextRegion;
    private final Map<UUID, Run> runs = new HashMap<>();
    public record Run(long enteredAt, int originChunkX, int originChunkZ) {}

    public static BackroomRunData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(BackroomRunData::load, BackroomRunData::new, KEY);
    }

    private static BackroomRunData load(CompoundTag tag) {
        BackroomRunData data = new BackroomRunData();
        data.nextRegion = tag.getLong("NextRegion");
        ListTag entries = tag.getList("Runs", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            try {
                UUID id = entry.getUUID("Player");
                data.runs.put(id, new Run(entry.getLong("EnteredAt"), entry.getInt("ChunkX"), entry.getInt("ChunkZ")));
            } catch (IllegalArgumentException ignored) { }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("NextRegion", nextRegion);
        ListTag entries = new ListTag();
        runs.forEach((uuid, run) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", uuid); entry.putLong("EnteredAt", run.enteredAt());
            entry.putInt("ChunkX", run.originChunkX()); entry.putInt("ChunkZ", run.originChunkZ());
            entries.add(entry);
        });
        tag.put("Runs", entries);
        return tag;
    }

    public Run start(UUID player) {
        long region = nextRegion++;
        // A 4096-chunk gap keeps independently assigned runs apart even after long exploration.
        int originX = Math.toIntExact((region + 1L) * 4096L);
        int originZ = Math.toIntExact(-((region + 1L) * 4096L));
        Run run = new Run(System.currentTimeMillis(), originX, originZ);
        runs.put(player, run);
        setDirty();
        return run;
    }

    public Run get(UUID player) { return runs.get(player); }
    public void finish(UUID player) { if (runs.remove(player) != null) setDirty(); }
}
