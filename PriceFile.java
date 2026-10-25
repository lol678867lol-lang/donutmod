package de.donutmod.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import de.donutmod.core.PriceBook;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Jedes Item im Spiel bekommt einen eigenen Preis. Beim ersten Start wird
 * config/donutmod-prices.json mit ALLEN Items erzeugt (bekannte Preise + Schaetzung).
 * Die Datei kann man von Hand aendern. Zum Neu-Erzeugen: Datei loeschen.
 */
public class PriceFile {
    /** Alle Basis-Preise werden damit multipliziert (DonutSMP-artiges Preisniveau). Zum Anpassen hier aendern. */
    static final long MULT = 40;
    /** Premium-Items mit festen Preisen (nicht multipliziert). */
    static final Map<String, Long> HIGH = Map.ofEntries(
            Map.entry("minecraft:mace", 2_500_000L), Map.entry("minecraft:elytra", 1_500_000L),
            Map.entry("minecraft:totem_of_undying", 800_000L), Map.entry("minecraft:heavy_core", 4_000_000L), Map.entry("minecraft:dragon_head", 6_000_000L),
            Map.entry("minecraft:nether_star", 1_500_000L), Map.entry("minecraft:beacon", 2_500_000L),
            Map.entry("minecraft:enchanted_golden_apple", 1_000_000L), Map.entry("minecraft:trident", 600_000L),
            Map.entry("minecraft:dragon_egg", 5_000_000L),
            Map.entry("minecraft:netherite_upgrade_smithing_template", 400_000L));

    static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("donutmod-prices-v3.json"); }

    public static void loadInto(PriceBook prices) {
        try {
            if (!Files.exists(file())) generate();
            Map<String, Long> m = new Gson().fromJson(Files.readString(file()), new TypeToken<Map<String, Long>>() {}.getType());
            if (m != null) m.forEach((id, price) -> prices.setBase(id, price));
        } catch (Exception e) { System.err.println("[DonutMod] Preisliste fehlgeschlagen: " + e); }
    }

    static void generate() throws java.io.IOException {
        Map<String, Long> known = new HashMap<>();
        for (Object[] r : DonutMod.KNOWN) known.put("minecraft:" + r[0], ((Number) r[1]).longValue());
        Map<String, Long> out = new TreeMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (id.equals("minecraft:air")) continue;
            Long k = known.get(id);
            Long hi = HIGH.get(id);
            out.put(id, hi != null ? hi : (k != null ? k : estimate(item, id)) * MULT);
        }
        Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(out));
    }

    static final String[][] MAT = {{"netherite", "1800"}, {"diamond", "500"}, {"golden", "120"}, {"gold", "120"},
            {"iron", "80"}, {"chainmail", "60"}, {"copper", "30"}, {"stone", "8"}, {"wooden", "5"}, {"leather", "15"}, {"turtle", "200"}};
    static final String[][] SHAPE = {{"sword", "2"}, {"pickaxe", "3"}, {"axe", "3"}, {"shovel", "1"}, {"hoe", "2"},
            {"helmet", "5"}, {"chestplate", "8"}, {"leggings", "7"}, {"boots", "4"}, {"horse_armor", "6"}, {"spear", "2"}};
    static final Object[][] WORDS = {
            {"spawn_egg", 5000}, {"smithing_template", 8000}, {"music_disc", 3000}, {"banner_pattern", 1500},
            {"pottery_sherd", 700}, {"_head", 2500}, {"skull", 2500}, {"shulker_box", 3500}, {"_ore", 150},
            {"raw_", 120}, {"nugget", 15}, {"stripped_", 24}, {"_log", 20}, {"_wood", 22}, {"planks", 6},
            {"_stairs", 9}, {"_slab", 5}, {"_wall", 8}, {"_fence", 10}, {"_door", 25}, {"trapdoor", 25},
            {"_sign", 20}, {"chest_boat", 90}, {"_boat", 60}, {"_wool", 25}, {"carpet", 10},
            {"concrete_powder", 18}, {"concrete", 20}, {"glazed_terracotta", 25}, {"terracotta", 15},
            {"stained_glass", 25}, {"glass", 12}, {"_bed", 120}, {"_dye", 20}, {"candle", 30}, {"coral", 60},
            {"sapling", 40}, {"seeds", 15}, {"leaves", 5}, {"_button", 12}, {"pressure_plate", 15},
            {"potion", 400}, {"_banner", 120}, {"minecart", 200}, {"rail", 40}, {"_bricks", 14}, {"brick", 12},
            {"copper", 30}};

    static long estimate(Item item, String id) {
        String p = id.substring(id.indexOf(':') + 1);
        double v = base(item, p);
        double jitter = 0.85 + (Math.abs(p.hashCode() % 31)) / 100.0; // 0.85 .. 1.15, fest pro Item
        return Math.max(1, Math.round(v * jitter));
    }

    static double base(Item item, String p) {
        for (String[] m : MAT) {
            if (!p.contains(m[0])) continue;
            for (String[] s : SHAPE) if (p.contains(s[0])) return Double.parseDouble(m[1]) * Double.parseDouble(s[1]);
        }
        for (Object[] w : WORDS) if (p.contains((String) w[0])) return ((Number) w[1]).doubleValue();
        ItemStack d = item.getDefaultInstance();
        double[] rarity = {50, 400, 2500, 15000};
        double r = rarity[Math.min(d.getRarity().ordinal(), 3)];
        int ms = d.getMaxStackSize();
        double f = ms >= 64 ? 1 : ms >= 16 ? 2.5 : 4;
        return r * f + Math.max(0, d.getMaxDamage()) * 3;
    }
}
