package com.servermod.backroomroguelike.registry;

import com.servermod.backroomroguelike.BackroomRoguelike;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BackroomRoguelike.MOD_ID);

    public static final RegistryObject<SoundEvent> EXTRACTION_POINT_ACTIVATE = SOUNDS.register(
            "block.extraction_point.activate",
            () -> SoundEvent.createVariableRangeEvent(
                    new ResourceLocation(BackroomRoguelike.MOD_ID, "block.extraction_point.activate"))
    );

    private ModSounds() {
    }
}
