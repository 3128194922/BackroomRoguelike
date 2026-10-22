package com.servermod.backroomroguelike;

import com.servermod.backroomroguelike.registry.ModBlocks;
import com.servermod.backroomroguelike.registry.ModCreativeTabs;
import com.servermod.backroomroguelike.registry.ModItems;
import com.servermod.backroomroguelike.world.RoomChunkGenerator;
import com.servermod.backroomroguelike.network.BackroomNetwork;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(BackroomRoguelike.MOD_ID)
public final class BackroomRoguelike {
    public static final String MOD_ID = "backroomroguelike";

    public BackroomRoguelike() {
        RoomChunkGenerator.register();
        BackroomNetwork.register();
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);
    }
}
