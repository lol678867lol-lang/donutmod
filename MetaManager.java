package de.donutmod.core;

import java.util.*;

/** Wechselnde Metas. Jedes Meta-Item bekommt einen zufälligen Bonus von 25 % bis 150 %.
 *  Wirkt auf /ah (Preis/Nachfrage) und Orders (Preis nur runter). Nicht auf /sell. */
public class MetaManager {
    public static final double MIN_BONUS = 0.25, MAX_BONUS = 1.50;
    private final Map<String, Double> metas = new HashMap<>();
    public double orderPriceFactor = 0.8;  // Order-Preis sinkt bei Meta

    public void roll(List<String> candidates, int count, Random rnd) {
        metas.clear();
        List<String> pool = new ArrayList<>(candidates);
        Collections.shuffle(pool, rnd);
        for (String id : pool.subList(0, Math.min(count, pool.size())))
            metas.put(id, MIN_BONUS + rnd.nextDouble() * (MAX_BONUS - MIN_BONUS));
    }
    public boolean isMeta(String id) { return metas.containsKey(id); }
    /** 0.25 - 1.50 für Meta-Items, sonst 0. */
    public double bonus(String id) { return metas.getOrDefault(id, 0.0); }
    public Map<String, Double> current() { return Collections.unmodifiableMap(metas); }
}
