package com.servermod.backroomroguelike.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;

public record RoomTemplate(ResourceLocation id, CompoundTag nbt) {
    public int[] size() {
        ListTag size = nbt.getList("size", Tag.TAG_INT);
        return new int[]{size.getInt(0), size.getInt(1), size.getInt(2)};
    }

    public List<BlockState> palette() {
        ListTag source = nbt.getList("palette", Tag.TAG_COMPOUND);
        List<BlockState> result = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            CompoundTag stateTag = source.getCompound(index);
            ResourceLocation blockId = ResourceLocation.tryParse(stateTag.getString("Name"));
            if (blockId == null) throw new IllegalStateException("Invalid block ID in room " + id + " palette[" + index + "]");
            int paletteIndex = index;
            Block block = BuiltInRegistries.BLOCK.getOptional(blockId)
                    .orElseThrow(() -> new IllegalStateException("Unregistered block " + blockId + " in room " + id + " palette[" + paletteIndex + "]"));
            BlockState state = block.defaultBlockState();
            CompoundTag properties = stateTag.getCompound("Properties");
            for (String name : properties.getAllKeys()) {
                Property<?> property = block.getStateDefinition().getProperty(name);
                if (property == null) throw new IllegalStateException("Unknown property " + name + " for " + blockId + " in room " + id);
                state = applyProperty(state, property, properties.getString(name), blockId);
            }
            result.add(state);
        }
        return result;
    }

    public void validate() {
        ListTag sizeTag = nbt.getList("size", Tag.TAG_INT);
        if (sizeTag.size() != 3) throw new IllegalStateException("Room " + id + " must have a 3-axis size");
        int[] dimensions = size();
        if (dimensions[0] != 16 || dimensions[1] != 16 || dimensions[2] != 16) {
            throw new IllegalStateException("Room " + id + " must have size [16,16,16], found " + java.util.Arrays.toString(dimensions));
        }
        List<BlockState> resolvedPalette = palette();
        ListTag blocks = nbt.getList("blocks", Tag.TAG_COMPOUND);
        for (int index = 0; index < blocks.size(); index++) {
            CompoundTag entry = blocks.getCompound(index);
            ListTag pos = entry.getList("pos", Tag.TAG_INT);
            if (pos.size() != 3) throw new IllegalStateException("Room " + id + " block[" + index + "] must have a 3-axis position");
            for (int axis = 0; axis < 3; axis++) {
                if (pos.getInt(axis) < 0 || pos.getInt(axis) >= dimensions[axis]) {
                    throw new IllegalStateException("Room " + id + " block[" + index + "] lies outside declared size");
                }
            }
            int paletteIndex = entry.getInt("state");
            if (paletteIndex < 0 || paletteIndex >= resolvedPalette.size()) {
                throw new IllegalStateException("Room " + id + " block[" + index + "] references invalid palette index " + paletteIndex);
            }
        }
    }

    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state, Property<T> property, String value, ResourceLocation blockId) {
        return property.getValue(value)
                .map(parsed -> state.setValue(property, parsed))
                .orElseThrow(() -> new IllegalStateException("Invalid value " + value + " for property " + property.getName() + " on " + blockId));
    }
}
