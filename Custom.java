package de.donutmod.fabric;

import de.donutmod.core.PriceBook;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Eigene Items (Shard-Werkzeuge, Spawner) und Traenke mit eigenen Preisen ueber Pseudo-Ids "donutmod:...". */
public final class Custom {
    private Custom() {}

    static final Map<String, Long> BASE = new LinkedHashMap<>();
    /** Schluessel (Custom-Data) der Shard-Werkzeuge und Spawner. */
    static final List<String> TAGGED = new ArrayList<>();
    /** Alle Trank-Pseudo-Ids (normal und Splash). */
    static final List<String> POTION_IDS = new ArrayList<>();
    static final Map<String, Holder<Potion>> POT = new LinkedHashMap<>();

    static {
        BASE.put("donutmod:shard_pickaxe", 26_000_000L);
        BASE.put("donutmod:shard_shovel", 18_000_000L);
        BASE.put("donutmod:shard_axe", 22_000_000L);
        BASE.put("donutmod:shard_hoe", 20_000_000L);
        BASE.put("donutmod:spawner_skeleton", 6_000_000L);
        BASE.put("donutmod:spawner_zombified_piglin", 5_000_000L);
        BASE.put("donutmod:spawner_creeper", 7_000_000L);
        BASE.put("donutmod:spawner_iron_golem", 9_000_000L);
        BASE.put("donutmod:spawner_zombie", 3_000_000L);
        BASE.put("donutmod:spawner_spider", 4_000_000L);
        for (String id : BASE.keySet()) TAGGED.add("donutmod_" + id.substring(9));

        pot("swiftness", Potions.SWIFTNESS, 50_000L);
        pot("long_swiftness", Potions.LONG_SWIFTNESS, 70_000L);
        pot("strong_swiftness", Potions.STRONG_SWIFTNESS, 90_000L);
        pot("strength", Potions.STRENGTH, 100_000L);
        pot("long_strength", Potions.LONG_STRENGTH, 120_000L);
        pot("strong_strength", Potions.STRONG_STRENGTH, 160_000L);
        pot("fire_resistance", Potions.FIRE_RESISTANCE, 60_000L);
        pot("long_fire_resistance", Potions.LONG_FIRE_RESISTANCE, 80_000L);
        pot("healing", Potions.HEALING, 60_000L);
        pot("strong_healing", Potions.STRONG_HEALING, 90_000L);
        pot("regeneration", Potions.REGENERATION, 80_000L);
        pot("long_regeneration", Potions.LONG_REGENERATION, 100_000L);
        pot("strong_regeneration", Potions.STRONG_REGENERATION, 120_000L);
        pot("night_vision", Potions.NIGHT_VISION, 40_000L);
        pot("invisibility", Potions.INVISIBILITY, 50_000L);
        pot("leaping", Potions.LEAPING, 40_000L);
        pot("slow_falling", Potions.SLOW_FALLING, 50_000L);
        pot("water_breathing", Potions.WATER_BREATHING, 40_000L);
        pot("turtle_master", Potions.TURTLE_MASTER, 90_000L);
    }

    private static void pot(String name, Holder<Potion> h, long price) {
        POT.put(name, h);
        BASE.put("donutmod:potion_" + name, price);
        BASE.put("donutmod:splash_potion_" + name, Math.round(price * 1.2));
        POTION_IDS.add("donutmod:potion_" + name);
        POTION_IDS.add("donutmod:splash_potion_" + name);
    }

    static void registerPrices(PriceBook p) { BASE.forEach((id, v) -> p.setBase(id, v)); }

    static String pretty(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.replace("minecraft:", "").split("_"))
            if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.ROOT)).append(' ');
        return sb.toString().trim();
    }

    static ItemStack tagged(Item item, String key, String name, String... lore) {
        ItemStack s = Gui.named(item, name, lore);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(key, true);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return s;
    }

    /** Baut das Item zu einer Pseudo-Id, oder null. */
    static ItemStack make(String id) {
        if (id == null || !id.startsWith("donutmod:")) return null;
        String k = id.substring(9);
        if (k.startsWith("shard_")) {
            String kind = k.substring(6);
            Item base; String desc;
            switch (kind) {
                case "pickaxe" -> { base = Items.NETHERITE_PICKAXE; desc = "§7Baut 3x3x3 auf einmal ab"; }
                case "shovel" -> { base = Items.NETHERITE_SHOVEL; desc = "§7Baut 3x3x3 auf einmal ab"; }
                case "axe" -> { base = Items.NETHERITE_AXE; desc = "§7Fällt ganze Bäume"; }
                case "hoe" -> { base = Items.NETHERITE_HOE; desc = "§7Erntet 5x5 und pflanzt automatisch neu"; }
                default -> { return null; }
            }
            return tagged(base, "donutmod_" + k, "§d§lShard " + pretty(kind), desc, "§8Amethyst-Klang beim Abbauen");
        }
        if (k.startsWith("spawner_"))
            return tagged(Items.SPAWNER, "donutmod_" + k, "§6§l" + pretty(k.substring(8)) + " Spawner",
                    "§7Platzieren, dann Rechtsklick: Menü mit Items", "§7Abbauen nur mit Silk Touch");
        if (k.startsWith("splash_potion_") || k.startsWith("potion_")) {
            boolean splash = k.startsWith("splash_potion_");
            Holder<Potion> h = POT.get(k.substring(splash ? 14 : 7));
            if (h == null) return null;
            ItemStack s = new ItemStack(splash ? Items.SPLASH_POTION : Items.POTION);
            s.set(DataComponents.POTION_CONTENTS, new PotionContents(h));
            return s;
        }
        return null;
    }

    static String key(ItemStack s) {
        if (s.isEmpty()) return null;
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return null;
        CompoundTag tag = cd.copyTag();
        for (String k : TAGGED) if (tag.contains(k)) return k;
        return null;
    }

    /** Pseudo-Id eines eigenen Items oder eines bekannten Tranks, sonst null. */
    static String idOf(ItemStack s) {
        String k = key(s);
        if (k != null) return "donutmod:" + k.substring(9);
        Item it = s.getItem();
        if (it == Items.POTION || it == Items.SPLASH_POTION) {
            PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
            if (pc != null) {
                Optional<Holder<Potion>> po = pc.potion();
                if (po.isPresent()) {
                    String n = po.get().getRegisteredName();
                    n = n.substring(n.indexOf(':') + 1);
                    if (POT.containsKey(n)) return "donutmod:" + (it == Items.POTION ? "potion_" : "splash_potion_") + n;
                }
            }
        }
        return null;
    }
}
