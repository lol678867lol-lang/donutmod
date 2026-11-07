package de.donutmod;

import com.google.gson.*;
import de.donutmod.core.RecipeGraph;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.item.Item;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import java.io.BufferedReader;
import java.util.*;

/** Liest alle Vanilla-/Mod-Rezepte (data/*/recipe/*.json) und füttert den RecipeGraph. */
final class RecipeLoader {
    static void load(MinecraftServer server, RecipeGraph g) {
        var found = server.getResourceManager().findResources("recipe", id -> id.getPath().endsWith(".json"));
        for (var e : found.entrySet()) {
            try (BufferedReader r = e.getValue().getReader()) {
                JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                parse(o, g);
            } catch (Exception ignored) { /* kaputtes/unbekanntes Rezept überspringen */ }
        }
    }

    private static void parse(JsonObject o, RecipeGraph g) {
        String type = o.get("type").getAsString().replace("minecraft:", "");
        String out; int outCount = 1;
        JsonElement res = o.has("result") ? o.get("result") : o.get("results");
        if (res != null && res.isJsonArray()) res = res.getAsJsonArray().isEmpty() ? null : res.getAsJsonArray().get(0);
        if (res == null) return;
        if (res.isJsonPrimitive()) out = res.getAsString();
        else {
            JsonObject ro = res.getAsJsonObject();
            out = (ro.has("id") ? ro.get("id") : ro.get("item")).getAsString();
            if (ro.has("count")) outCount = ro.get("count").getAsInt();
        }
        List<RecipeGraph.Ing> ings = new ArrayList<>();
        switch (type) {
            case "crafting_shaped" -> {
                Map<Character, Integer> counts = new HashMap<>();
                for (JsonElement row : o.getAsJsonArray("pattern"))
                    for (char c : row.getAsString().toCharArray()) if (c != ' ') counts.merge(c, 1, Integer::sum);
                JsonObject key = o.getAsJsonObject("key");
                for (var k : counts.entrySet()) {
                    List<String> opts = options(key.get(String.valueOf(k.getKey())));
                    if (opts.isEmpty()) return;
                    ings.add(new RecipeGraph.Ing(opts, k.getValue()));
                }
            }
            case "crafting_shapeless" -> {
                for (JsonElement el : o.getAsJsonArray("ingredients")) {
                    List<String> opts = options(el);
                    if (opts.isEmpty()) return;
                    ings.add(new RecipeGraph.Ing(opts, 1));
                }
            }
            case "smelting", "blasting", "smoking", "campfire_cooking" -> {
                List<String> opts = options(o.get("ingredient"));
                if (opts.isEmpty()) return;
                ings.add(new RecipeGraph.Ing(opts, 1));
            }
            case "smithing_transform" -> {
                for (String f : new String[]{"template", "base", "addition"}) {
                    List<String> opts = options(o.get(f));
                    if (opts.isEmpty()) return;
                    ings.add(new RecipeGraph.Ing(opts, 1));
                }
            }
            default -> {
                // Unbekannter (Mod-)Rezepttyp: generisch versuchen, "ingredients"/"ingredient" zu lesen
                JsonElement all = o.has("ingredients") ? o.get("ingredients") : o.get("ingredient");
                if (all == null) return;
                if (all.isJsonArray()) {
                    for (JsonElement el : all.getAsJsonArray()) {
                        List<String> opts = options(el);
                        if (opts.isEmpty()) return;
                        ings.add(new RecipeGraph.Ing(opts, 1));
                    }
                } else {
                    List<String> opts = options(all);
                    if (opts.isEmpty()) return;
                    ings.add(new RecipeGraph.Ing(opts, 1));
                }
                if (ings.isEmpty()) return;
            }
        }
        g.add(new RecipeGraph.Recipe(out, outCount, ings));
    }

    /** Zutat als Liste möglicher Item-IDs (Tags werden aufgelöst). */
    private static List<String> options(JsonElement el) {
        List<String> out = new ArrayList<>();
        if (el == null) return out;
        if (el.isJsonPrimitive()) add(out, el.getAsString());
        else if (el.isJsonObject()) {                       // altes/Mod-Format: {"item": "..."} oder {"tag": "..."}
            JsonObject jo = el.getAsJsonObject();
            if (jo.has("item")) add(out, jo.get("item").getAsString());
            else if (jo.has("id")) add(out, jo.get("id").getAsString());
            else if (jo.has("tag")) add(out, "#" + jo.get("tag").getAsString());
        } else if (el.isJsonArray()) for (JsonElement x : el.getAsJsonArray()) out.addAll(options(x));
        return out;
    }

    private static void add(List<String> out, String s) {
        if (!s.startsWith("#")) { out.add(s); return; }
        TagKey<Item> tag = TagKey.of(RegistryKeys.ITEM, Identifier.of(s.substring(1)));
        for (RegistryEntry<Item> e : Registries.ITEM.iterateEntries(tag))
            e.getKey().ifPresent(k -> out.add(k.getValue().toString()));
    }
}
