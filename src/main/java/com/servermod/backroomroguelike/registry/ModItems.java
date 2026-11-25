package com.servermod.backroomroguelike.registry;

import com.servermod.backroomroguelike.BackroomRoguelike;
import com.servermod.backroomroguelike.item.BackroomEntryItem;
import com.servermod.backroomroguelike.item.RadioCallerItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BackroomRoguelike.MOD_ID);

    public static final RegistryObject<Item> EXTRACTION_POINT = ITEMS.register(
            "extraction_point", () -> new BlockItem(ModBlocks.EXTRACTION_POINT.get(), new Item.Properties())
    );
    public static final RegistryObject<Item> BACKROOM_ENTRY = ITEMS.register(
            "backroom_entry", () -> new BackroomEntryItem(new Item.Properties().stacksTo(1))
    );
    public static final RegistryObject<Item> RADIO_CALLER = ITEMS.register(
            "radio_caller", () -> new RadioCallerItem(new Item.Properties().stacksTo(1))
    );

    private ModItems() {
    }
}
