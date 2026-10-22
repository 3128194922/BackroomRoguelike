package com.servermod.backroomroguelike.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

public record RoomLocation(ResourceLocation roomId, ChunkPos chunkPos, BlockPos origin, double distanceSquared) {}
