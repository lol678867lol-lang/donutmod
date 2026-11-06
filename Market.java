package de.donutmod;

import de.donutmod.core.*;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.*;

/** Globaler Zustand (Singleplayer: Server läuft im selben Prozess wie der Client). */
public final class Market {
    public static boolean ready = false;
    public static MinecraftServer server;
    public static final Random rnd = new Random();

    public static PriceBook prices;
    public static MetaManager meta;
    public static Economy eco;
    public static Orders orders;
    public static AuctionHouse ah;
    public static SellService sell;
    public static RecipeGraph recipes;

    /** Bot-Katalog: id, max. Menge, Verzauberungen. */
    record Entry(String id, int maxCount, Map<String, Integer> ench) {}
    static final List<Entry> CATALOG = List.of(
        new Entry("minecraft:kelp", 64, Map.of()),
        new Entry("minecraft:sugar_cane", 64, Map.of()),
        new Entry("minecraft:cactus", 64, Map.of()),
        new Entry("minecraft:bone", 64, Map.of()),
        new Entry("minecraft:obsidian", 64, Map.of()),
        new Entry("minecraft:ender_pearl", 16, Map.of()),
        new Entry("minecraft:golden_apple", 16, Map.of()),
        new Entry("minecraft:diamond", 64, Map.of()),
        new Entry("minecraft:netherite_ingot", 16, Map.of()),
        new Entry("minecraft:netherite_chestplate", 1,
            Map.of("minecraft:protection", 4, "minecraft:unbreaking", 3, "minecraft:mending", 1)),
        new Entry("minecraft:netherite_sword", 1,
            Map.of("minecraft:sharpness", 5, "minecraft:unbreaking", 3, "minecraft:mending", 1)),
        new Entry("minecraft:elytra", 1, Map.of("minecraft:mending", 1))
    );

    public static void init(MinecraftServer s) {
        server = s;
        prices = new PriceBook();
        prices.setBase("minecraft:kelp", 40);
        prices.setBase("minecraft:sugar_cane", 30);
        prices.setBase("minecraft:cactus", 25);
        prices.setBase("minecraft:bone", 20);
        prices.setBase("minecraft:obsidian", 250);
        prices.setBase("minecraft:ender_pearl", 400);
        prices.setBase("minecraft:golden_apple", 1_500);
        prices.setBase("minecraft:diamond", 5_000);
        prices.setBase("minecraft:netherite_ingot", 50_000);
        prices.setBase("minecraft:elytra", 500_000);
        prices.setEnchantValue("minecraft:protection", 100_000);
        prices.setEnchantValue("minecraft:sharpness", 80_000);
        prices.setEnchantValue("minecraft:unbreaking", 30_000);
        prices.setEnchantValue("minecraft:mending", 200_000);
        // Alle anderen Items: Preis nach Seltenheit, damit wirklich alles verkaufbar ist
        java.util.function.ToLongFunction<String> raw = id -> {
            try {
                Item it = Registries.ITEM.get(Identifier.of(id));
                return switch (new ItemStack(it).getRarity()) {
                    case COMMON -> 100L;
                    case UNCOMMON -> 1_000L;
                    case RARE -> 25_000L;
                    case EPIC -> 250_000L;
                };
            } catch (Exception e) { return 100L; }
        };
        // Alles mit Rezept wird aus den Grundmaterialien berechnet (+10 %), Rest = Rohstoff nach Seltenheit
        recipes = new RecipeGraph(prices, raw);
        RecipeLoader.load(s, recipes);
        prices.fallback = recipes::price;
        meta = new MetaManager();
        eco = new Economy();
        orders = new Orders(prices, meta, eco);
        ah = new AuctionHouse(prices, meta, eco);
        sell = new SellService(prices, orders, eco);
        rollMetas();
        for (int i = 0; i < 12; i++) botList();
        for (int i = 0; i < 6; i++) botOrder();
        ready = true;
    }

    public static void shutdown() { ready = false; }

    public static void rollMetas() {
        meta.roll(CATALOG.stream().map(Entry::id).toList(), 3, rnd);
    }

    public static void botList() {
        Entry e = CATALOG.get(rnd.nextInt(CATALOG.size()));
        ah.botList(new Stack(e.id(), 1 + rnd.nextInt(e.maxCount()), e.ench()), rnd);
    }

    public static void botOrder() {
        Entry e = CATALOG.get(rnd.nextInt(CATALOG.size()));
        orders.botOrder(e.id(), e.ench(), 1 + rnd.nextInt(e.maxCount()), rnd);
    }

    /** Wird jede Spielminute (1200 Ticks) aufgerufen. */
    public static void minuteTick() {
        ah.tickBotBuyers(rnd);
        long botListings = ah.all().stream().filter(l -> l.seller == null).count();
        if (botListings < 15) botList();
        long botOrders = orders.all().stream().filter(o -> o.owner == null).count();
        if (botOrders < 8) botOrder();
        // Spieler-Orders werden von Bots geliefert (zufällig, nur wenn Spieler online)
        for (Orders.Order o : new ArrayList<>(orders.all())) {
            if (o.owner == null || rnd.nextDouble() > 0.3) continue;
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(o.owner);
            if (p == null) continue;
            orders.remove(o);
            give(p, new Stack(o.itemId, o.remaining, o.enchants));
            p.sendMessage(net.minecraft.text.Text.literal("Deine Order wurde geliefert: " + o.remaining + "x " + o.itemId), false);
        }
    }

    // ---------- Umwandlung Minecraft <-> Core (Haltbarkeit wird bewusst ignoriert) ----------

    public static Stack toCore(ItemStack s) {
        Map<String, Integer> ench = new HashMap<>();
        collect(s.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT), ench);
        collect(s.getOrDefault(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT), ench);
        return new Stack(Registries.ITEM.getId(s.getItem()).toString(), s.getCount(), ench);
    }

    private static void collect(ItemEnchantmentsComponent c, Map<String, Integer> out) {
        for (var e : c.getEnchantmentEntries())
            e.getKey().getKey().ifPresent(k -> out.put(k.getValue().toString(), e.getIntValue()));
    }

    public static ItemStack toMc(Stack s) {
        Item item = Registries.ITEM.get(Identifier.of(s.id()));
        ItemStack st = new ItemStack(item, Math.max(1, Math.min(s.count(), item.getMaxCount())));
        var reg = server.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        for (var e : s.enchants().entrySet()) {
            reg.getOptional(RegistryKey.of(RegistryKeys.ENCHANTMENT, Identifier.of(e.getKey())))
               .ifPresent(entry -> st.addEnchantment(entry, e.getValue()));
        }
        return st;
    }

    /** Gibt dem Spieler das Item (in Stacks gesplittet). */
    public static void give(ServerPlayerEntity p, Stack s) {
        int left = s.count();
        int max = Registries.ITEM.get(Identifier.of(s.id())).getMaxCount();
        while (left > 0) {
            int n = Math.min(left, max);
            p.getInventory().offerOrDrop(toMc(new Stack(s.id(), n, s.enchants())));
            left -= n;
        }
    }

    public static String fmt(long v) {
        if (v >= 1_000_000_000L) return String.format(Locale.GERMANY, "%.2f Mrd", v / 1e9);
        if (v >= 1_000_000L) return String.format(Locale.GERMANY, "%.2f M", v / 1e6);
        if (v >= 1_000L) return String.format(Locale.GERMANY, "%.1f K", v / 1e3);
        return Long.toString(v);
    }
}
