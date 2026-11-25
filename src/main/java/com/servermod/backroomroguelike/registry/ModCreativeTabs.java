package com.servermod.backroomroguelike.registry;

import com.servermod.backroomroguelike.BackroomRoguelike;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BackroomRoguelike.MOD_ID);

    public static final RegistryObject<CreativeModeTab> BACKROOM = TABS.register(
            "backroom",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.backroomroguelike"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> ModItems.BACKROOM_ENTRY.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.BACKROOM_ENTRY.get());
                        output.accept(ModItems.RADIO_CALLER.get());
                        output.accept(ModItems.EXTRACTION_POINT.get());
                    })
                    .build()
    );

    private ModCreativeTabs() {
    }
}
