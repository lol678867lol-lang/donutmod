package de.donutmod.core;

import java.util.HashMap;
import java.util.Map;

/** Feste Preise. /sell hat KEINEN Meta-Faktor, Haltbarkeit ist egal. */
public class PriceBook {
    private final Map<String, Long> base = new HashMap<>();
    private final Map<String, Long> enchantValue = new HashMap<>();
    public long defaultBase = 100;
    public long defaultEnchantPerLevel = 5_000;
    /** Globaler Preisfaktor für alle Items (1.25 = 25 % teurer). */
    public double priceMultiplier = 1.25;

    public void setBase(String id, long price) { base.put(id, price); }
    public void setEnchantValue(String enchantId, long perLevel) { enchantValue.put(enchantId, perLevel); }

    /** Preis für EIN Item inkl. Verzauberungen. */
    public long unitPrice(String id, Map<String, Integer> enchants) {
        long p = base.getOrDefault(id, defaultBase);
        for (var e : enchants.entrySet())
            p += enchantValue.getOrDefault(e.getKey(), defaultEnchantPerLevel) * e.getValue();
        return Math.round(p * priceMultiplier);
    }

    public long unitPrice(Stack s) { return unitPrice(s.id(), s.enchants()); }
    public long stackPrice(Stack s) { return unitPrice(s) * s.count(); }
}
