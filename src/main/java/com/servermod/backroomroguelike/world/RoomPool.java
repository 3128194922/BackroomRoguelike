package com.servermod.backroomroguelike.world;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;

/** Immutable directory pool snapshot, rebuilt atomically during datapack reload. */
public final class RoomPool {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile Node root = new Node();

    private RoomPool() {}

    public static void reload(ResourceManager manager) {
        Node fresh = new Node();
        Map<ResourceLocation, net.minecraft.server.packs.resources.Resource> resources = manager.listResources(
                "structures/rooms", id -> id.getPath().endsWith(".nbt"));
        resources.entrySet().stream()
                .filter(entry -> entry.getKey().getNamespace().equals("backroomroguelike"))
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
                .forEach(entry -> {
                    try (var input = entry.getValue().open()) {
                        CompoundTag nbt = NbtIo.readCompressed(input);
                        String relative = entry.getKey().getPath().substring("structures/rooms/".length());
                        String[] parts = relative.substring(0, relative.length() - 4).split("/");
                        RoomTemplate template = new RoomTemplate(
                                new ResourceLocation(entry.getKey().getNamespace(), "rooms/" + String.join("/", parts)), nbt);
                        template.validate();
                        Node node = fresh;
                        for (int i = 0; i < parts.length - 1; i++) node = node.folders.computeIfAbsent(parts[i], ignored -> new Node());
                        node.rooms.put(parts[parts.length - 1], template);
                    } catch (Exception exception) {
                        LOGGER.error("Skipping invalid Backroom room resource {}", entry.getKey(), exception);
                    }
                });
        if (!fresh.hasRooms()) {
            LOGGER.error("No valid rooms found in data/backroomroguelike/structures/rooms; keeping the previous room pool");
            return;
        }
        root = fresh;
    }

    public static boolean isReady() { return root.hasRooms(); }

    /** Folders and NBT files in each folder are equal candidates; the hash is refined at each folder level. */
    public static RoomTemplate select(int chunkX, int chunkZ) {
        Node node = root;
        if (!node.hasRooms()) throw new IllegalStateException("No valid Backroom room resources are loaded");
        long hash = mix(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL));
        int depth = 0;
        while (true) {
            List<Choice> choices = new ArrayList<>(node.rooms.size() + node.folders.size());
            node.rooms.forEach((name, room) -> choices.add(new Choice(name, room, null)));
            node.folders.forEach((name, folder) -> choices.add(new Choice(name, null, folder)));
            choices.sort(Comparator.comparing(Choice::name));
            if (choices.isEmpty()) throw new IllegalStateException("Selected an empty Backroom room pool");
            Choice selected = choices.get((int) Long.remainderUnsigned(mix(hash + depth), choices.size()));
            if (selected.room() != null) return selected.room();
            node = selected.folder();
            hash = mix(hash ^ (0x9e3779b97f4a7c15L * (depth + 1L)));
            depth++;
        }
    }

    public static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static final class Node {
        private final Map<String, RoomTemplate> rooms = new TreeMap<>();
        private final Map<String, Node> folders = new TreeMap<>();
        private boolean hasRooms() { return !rooms.isEmpty() || folders.values().stream().anyMatch(Node::hasRooms); }
    }
    private record Choice(String name, RoomTemplate room, Node folder) {}
}
