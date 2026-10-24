package de.donutmod.core;

import java.util.*;

public class Economy {
    private final Map<UUID, Long> balances = new HashMap<>();
    public long get(UUID p) { return balances.getOrDefault(p, 0L); }
    public void add(UUID p, long amount) { balances.merge(p, amount, Long::sum); }
    public boolean take(UUID p, long amount) {
        if (get(p) < amount) return false;
        add(p, -amount);
        return true;
    }
    public Map<UUID, Long> snapshot() { return new HashMap<>(balances); }
    public void load(Map<UUID, Long> data) { balances.clear(); balances.putAll(data); }
}
