package com.servermod.backroomroguelike.world;

import com.mojang.logging.LogUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
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
    private static volatile RoomTemplate initialRoom;

    private RoomPool() {}

    public static void reload(ResourceManager manager) {
        PoolIndex index = findPoolIndex(manager);
        if (index == null) {
            LOGGER.error("No valid index.json found under data/*/structures/rooms; keeping the previous room pool");
            return;
        }

        Node fresh = new Node();
        Map<ResourceLocation, List<Resource>> resources = manager.listResourceStacks(
                "structures/rooms", id -> id.getPath().endsWith(".nbt"));
        Map<ResourceLocation, RoomTemplate> loadedRooms = new TreeMap<>(Comparator.comparing(ResourceLocation::toString));
        resources.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
                .forEach(entry -> {
                    for (Resource resource : entry.getValue()) {
                        if (!resource.sourcePackId().equals(index.sourcePackId())) continue;
                        try (var input = resource.open()) {
                            CompoundTag nbt = NbtIo.readCompressed(input);
                            String relative = entry.getKey().getPath().substring("structures/rooms/".length());
                            String[] parts = relative.substring(0, relative.length() - 4).split("/");
                            RoomTemplate template = new RoomTemplate(
                                    ResourceLocation.fromNamespaceAndPath(entry.getKey().getNamespace(), "rooms/" + String.join("/", parts)), nbt);
                            template.validate();
                            Node node = fresh;
                            for (int i = 0; i < parts.length - 1; i++) node = node.folders.computeIfAbsent(parts[i], ignored -> new Node());
                            node.rooms.put(parts[parts.length - 1], template);
                            loadedRooms.put(template.id(), template);
                        } catch (Exception exception) {
                            LOGGER.error("Skipping invalid Backroom room resource {} from pack {}", entry.getKey(), resource.sourcePackId(), exception);
                        }
                    }
                });
        fresh.prepare();
        if (!fresh.hasRooms()) {
            LOGGER.error("No valid room structures found in the index.json data pack {}; keeping the previous room pool", index.sourcePackId());
            return;
        }
        RoomTemplate selectedInitialRoom = loadedRooms.get(index.initialStructure());
        if (selectedInitialRoom == null) {
            LOGGER.error("Initial room {} from index.json was not found in data pack {}; keeping the previous room pool",
                    index.initialStructure(), index.sourcePackId());
            return;
        }
        initialRoom = selectedInitialRoom;
        root = fresh;
        LOGGER.info("Loaded {} Backroom rooms from data pack {} (priority {}), initial room is {}",
                loadedRooms.size(), index.sourcePackId(), index.priority(), selectedInitialRoom.id());
    }

    private static PoolIndex findPoolIndex(ResourceManager manager) {
        Map<ResourceLocation, List<Resource>> indexes = manager.listResourceStacks(
                "structures/rooms", id -> id.getPath().equals("structures/rooms/index.json"));
        PoolIndex winner = null;
        for (Map.Entry<ResourceLocation, List<Resource>> entry : indexes.entrySet()) {
            for (Resource resource : entry.getValue()) {
                try (var reader = resource.openAsReader()) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (!parsed.isJsonObject()) throw new IllegalArgumentException("index.json must contain a JSON object");
                    JsonObject json = parsed.getAsJsonObject();
                    int priority = json.has("priority") ? json.get("priority").getAsInt() : 0;
                    ResourceLocation initialStructure = parseInitialStructure(entry.getKey(), json.get("initial_structure").getAsString());
                    PoolIndex candidate = new PoolIndex(priority, entry.getKey(), resource.sourcePackId(), initialStructure);
                    boolean builtInWinsTie = winner != null && candidate.priority() == winner.priority()
                            && candidate.indexId().getNamespace().equals("backroomroguelike")
                            && !winner.indexId().getNamespace().equals("backroomroguelike");
                    if (winner == null || candidate.priority() > winner.priority() || builtInWinsTie) {
                        winner = candidate;
                    }
                } catch (Exception exception) {
                    LOGGER.error("Ignoring invalid Backroom pool index {} from pack {}", entry.getKey(), resource.sourcePackId(), exception);
                }
            }
        }
        return winner;
    }

    private static ResourceLocation parseInitialStructure(ResourceLocation indexId, String value) {
        String namespace = indexId.getNamespace();
        String path = value.replace('\\', '/');
        if (path.indexOf(':') >= 0) {
            ResourceLocation qualified = ResourceLocation.parse(path);
            namespace = qualified.getNamespace();
            path = qualified.getPath();
        }
        if (path.startsWith("structures/rooms/")) path = path.substring("structures/rooms/".length());
        else if (path.startsWith("rooms/")) path = path.substring("rooms/".length());
        if (path.endsWith(".nbt")) path = path.substring(0, path.length() - 4);
        return ResourceLocation.fromNamespaceAndPath(namespace, "rooms/" + path);
    }

    public static boolean isReady() { return root.hasRooms() && initialRoom != null; }

    /** Assigns the configured safe room to each run's reserved entrance chunk. */
    public static RoomTemplate selectForChunk(int chunkX, int chunkZ) {
        if (isRunEntranceChunk(chunkX, chunkZ) && initialRoom != null) return initialRoom;
        return select(chunkX, chunkZ);
    }

    private static boolean isRunEntranceChunk(int chunkX, int chunkZ) {
        return chunkX > 0 && chunkX % 4096 == 0 && chunkZ == -chunkX;
    }

    /** Folders and NBT files in each folder are equal candidates; the hash is refined at each folder level. */
    public static RoomTemplate select(int chunkX, int chunkZ) {
        Node node = root;
        if (!node.hasRooms()) throw new IllegalStateException("No valid Backroom room resources are loaded");
        long hash = mix(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL));
        int depth = 0;
        while (true) {
            List<Choice> choices = node.choices;
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
        private List<Choice> choices = List.of();

        private void prepare() {
            folders.values().forEach(Node::prepare);
            List<Choice> prepared = new ArrayList<>(rooms.size() + folders.size());
            rooms.forEach((name, room) -> prepared.add(new Choice(name, room, null)));
            folders.forEach((name, folder) -> prepared.add(new Choice(name, null, folder)));
            prepared.sort(Comparator.comparing(Choice::name));
            choices = List.copyOf(prepared);
        }

        private boolean hasRooms() { return !choices.isEmpty(); }
    }
    private record Choice(String name, RoomTemplate room, Node folder) {}
    private record PoolIndex(int priority, ResourceLocation indexId, String sourcePackId, ResourceLocation initialStructure) {}
}
