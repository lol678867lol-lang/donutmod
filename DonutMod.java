package de.donutmod.fabric;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import de.donutmod.core.*;
import de.donutmod.core.Stack;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.nio.file.*;
import java.util.*;

/** Fabric-Anbindung (Mojang-Mappings, MC 1.21.11) an den versionsunabhaengigen Core. */
public class DonutMod implements ModInitializer {
    static final Random RND = new Random();
    static final List<String> META_POOL = List.of("minecraft:kelp", "minecraft:diamond", "minecraft:iron_ingot",
            "minecraft:gold_ingot", "minecraft:sea_lantern", "minecraft:cactus", "minecraft:bone_meal");

    final PriceBook prices = new PriceBook();
    final MetaManager meta = new MetaManager();
    final Economy eco = new Economy();
    final Orders orders = new Orders(prices, meta, eco);
    final AuctionHouse ah = new AuctionHouse(prices, meta, eco);
    final SellService sell = new SellService(prices, orders, eco);
    /** Echte ItemStacks zu den AH-Angeboten (Core kennt nur Stack-Beschreibungen). */
    final Map<Integer, ItemStack> ahItems = new HashMap<>();
    /** Pro Spieler eigenes Sidebar-Scoreboard (rechts, mittig) mit dem eigenen Kontostand. */
    final Map<UUID, Objective> boards = new HashMap<>();
    static final Gson GSON = new Gson();
    static Path saveFile() { return FabricLoader.getInstance().getConfigDir().resolve("donutmod-balances.json"); }

    @Override
    public void onInitialize() {
        prices.setBase("minecraft:kelp", 50);
        prices.setBase("minecraft:diamond", 500);
        prices.setBase("minecraft:iron_ingot", 80);
        prices.setBase("minecraft:gold_ingot", 120);
        prices.setBase("minecraft:sea_lantern", 300);
        prices.setBase("minecraft:netherite_ingot", 5_000);
        prices.setBase("minecraft:netherite_chestplate", 60_000);
        prices.setEn
