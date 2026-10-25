package de.donutmod.fabric;

import de.donutmod.core.PriceBook;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Eigene Items: Shard-Werkzeuge und Spawner. Erkennung ueber Custom-Data, Preise ueber Pseudo-Ids "donutmod:...". */
public final class Custom {
    private Custom() {}

    static final Map<String, Long> BASE = new LinkedHashMap<>();
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
        return null;
    }

    static String key(ItemStack s) {
        if (s.isEmpty()) return null;
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return null;
        CompoundTag tag = cd.copyTag();
        for (String id : BASE.keySet()) {
            String k = "donutmod_" + id.substring(9);
            if (tag.contains(k)) return k;
        }
        return null;
    }

    /** Pseudo-Id eines eigenen Items, sonst null. */
    static String idOf(ItemStack s) {
        String k = key(s);
        return k == null ? null : "donutmod:" + k.substring(9);
    }
}
