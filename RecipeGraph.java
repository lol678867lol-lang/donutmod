package de.donutmod.core;

import java.util.*;
import java.util.function.ToLongFunction;

/**
 * Preise aus Rezepten: Preis = Summe der Zutaten * markup / Anzahl Ergebnisse.
 * markup > 1  =>  ein Item ist immer mehr wert als seine Zutaten (Stone > Cobblestone).
 * Items ohne Rezept (Rohstoffe) nutzen raw(); explizite Preise im PriceBook haben Vorrang.
 */
public class RecipeGraph {
    public record Ing(List<String> options, int count) {}
    public record Recipe(String out, int outCount, List<Ing> ings) {}

    public double markup = 1.10;
    private final Map<String, List<Recipe>> byOut = new HashMap<>();
    private final Map<String, Long> memo = new HashMap<>();
    private final PriceBook prices;
    private final ToLongFunction<String> raw;

    public RecipeGraph(PriceBook prices, ToLongFunction<String> raw) { this.prices = prices; this.raw = raw; }

    public void add(Recipe r) { byOut.computeIfAbsent(r.out(), k -> new ArrayList<>()).add(r); memo.clear(); }

    public long price(String id) { return price(id, new HashSet<>()); }

    private long price(String id, Set<String> visiting) {
        Long e = prices.explicit(id);
        if (e != null) return e;
        Long m = memo.get(id);
        if (m != null) return m;
        List<Recipe> rs = byOut.get(id);
        if (rs != null && visiting.add(id)) {
            double best = -1;
            for (Recipe r : rs) {
                double sum = 0; boolean ok = true;
                for (Ing ing : r.ings()) {
                    long cheapest = -1;
                    for (String opt : ing.options()) {
                        if (visiting.contains(opt)) continue;   // Zyklen (z.B. Eisenblock <-> Barren) vermeiden
                        long p = price(opt, visiting);
                        if (p > 0 && (cheapest < 0 || p < cheapest)) cheapest = p;
                    }
                    if (cheapest < 0) { ok = false; break; }
                    sum += (double) cheapest * ing.count();
                }
                if (!ok) continue;
                double per = sum * markup / Math.max(1, r.outCount());
                best = best < 0 ? per : Math.min(best, per);
            }
            visiting.remove(id);
            if (best > 0) {
                long v = Math.max(1, (long) Math.ceil(best));
                memo.put(id, v);
                return v;
            }
        }
        long v = raw.applyAsLong(id);
        if (!visiting.contains(id)) memo.put(id, v);
        return v;
    }
}
