package com.example.testmod.registries;

import com.example.testmod.TestMod;
import com.example.testmod.loot.RandomizeScrollFunction;
import net.minecraft.core.Registry;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraftforge.common.loot.GlobalLootModifierSerializer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class LootRegistry {
    public static final DeferredRegister<LootItemFunctionType> LOOT_FUNCTIONS = DeferredRegister.create(Registry.LOOT_FUNCTION_REGISTRY, TestMod.MODID);
    public static void register(IEventBus eventBus){
        LOOT_FUNCTIONS.register(eventBus);
    }

    public static final RegistryObject<LootItemFunctionType> RANDOMIZE_SCROLL_FUNCTION = LOOT_FUNCTIONS.register("randomize_scroll", () -> new LootItemFunctionType(new RandomizeScrollFunction.Serializer()));
}
