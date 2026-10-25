package de.donutmod.fabric;

import de.donutmod.core.AuctionHouse;
import de.donutmod.core.Orders;
import de.donutmod.core.Stack;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Bot-Markt: haelt Angebote/Orders auf Mindestmengen (speicherabhaengig skaliert), erzeugt jede Sekunde 10 neue
 * Eintraege und laesst 10 alte verschwinden. Bot-Eintraege sind leichtgewichtig (nur Core-Stack, kein ItemStack).
 */
public final class Market {
    final DonutMod m;
    long minAh, minOrders;
    /** Obergrenze, damit der Markt durch +3 pro Sekunde nicht unbegrenzt waechst (Speicher). */
    double capFactor = 1.0;
    long maxAh, maxOrders;
    boolean reduced;
    final Map<String, Integer> ahCount = new HashMap<>();
    final Map<String, Integer> orderCount = new HashMap<>();
    final Map<Item, List<Holder.Reference<Enchantment>>> compat = new HashMap<>();
    static final Map<String, Integer> NONE = Map.of();
    static final Random RND = DonutMod.RND;

    Market(DonutMod m) { this.m = m; }

    void init() {
        long max = Runtime.getRuntime().maxMemory();
        // Das Spiel selbst braucht ca. 1,6 GB; vom Rest darf der Markt die Haelfte nutzen
        double budget = Math.max(0.0, (max - 1.6 * 1024 * 1024 * 1024) * 0.5);
        double need = DonutMod.REQ_AH * 110.0 + DonutMod.REQ_ORDERS * 100.0;
        double scale = Math.min(1.0, budget / need);
        capFactor = Math.max(1.0, Math.min(1.5, budget / need));
        minAh = Math.max(10_000L, (long) (DonutMod.REQ_AH * scale));
        minOrders = Math.max(5_000L, (long) (DonutMod.REQ_ORDERS * scale));
        maxAh = (long) (minAh * capFactor);
        maxOrders = (long) (minOrders * capFactor);
        reduced = scale < 0.999;
        System.out.println("[DonutMod] Markt-Ziel: " + minAh + " Angebote, " + minOrders + " Orders (RAM-Faktor " + String.format("%.2f", scale) + ")");
        for (AuctionHouse.Listing l : m.ah.all()) if (l.stack.enchants().isEmpty()) ahCount.merge(l.stack.id(), 1, Integer::sum);
        for (Orders.Order o : m.orders.all()) if (o.enchants.isEmpty()) orderCount.merge(o.itemId, 1, Integer::sum);
    }

    // ---------- Zaehler ----------
    void ahRemoved(AuctionHouse.Listing l) {
        if (l.stack.enchants().isEmpty()) ahCount.merge(l.stack.id(), -1, (a, b) -> Math.max(0, a + b));
    }

    void ordersRemoved(Orders.Order o) {
        if (o.enchants.isEmpty()) orderCount.merge(o.itemId, -1, (a, b) -> Math.max(0, a + b));
    }

    // ---------- Erzeugen ----------
    Map<String, Integer> randomEnchants(Item it) {
        List<Holder.Reference<Enchantment>> ok = compat.computeIfAbsent(it, k -> {
            List<Holder.Reference<Enchantment>> r = new ArrayList<>();
            ItemStack st = new ItemStack(k);
            boolean book = k == Items.ENCHANTED_BOOK;
            if (book || st.isEnchantable())
                for (Holder.Reference<Enchantment> h : m.enchList) if (book || h.value().isSupportedItem(st)) r.add(h);
            return r;
        });
        if (ok.isEmpty()) return null;
        boolean book = it == Items.ENCHANTED_BOOK;
        int n = Math.min(ok.size(), 1 + RND.nextInt(book ? 2 : 4));
        Map<String, Integer> out = new HashMap<>();
        while (out.size() < n) {
            Holder.Reference<Enchantment> h = ok.get(RND.nextInt(ok.size()));
            int max = Math.max(1, h.value().getMaxLevel());
            out.put(h.getRegisteredName(), RND.nextInt(3) == 0 ? max : 1 + RND.nextInt(max));
        }
        return out;
    }

    void addAh(int n) {
        List<Item> pool = m.pool;
        if (pool.isEmpty()) return;
        int made = 0, tries = 0;
        while (made < n && tries++ < n * 30) {
            Item it = pool.get(RND.nextInt(pool.size()));
            String id = m.idOf(it);
            boolean book = it == Items.ENCHANTED_BOOK;
            Map<String, Integer> ench = NONE;
            if (book || RND.nextInt(4) == 0) {
                Map<String, Integer> e = randomEnchants(it);
                if (e != null) ench = e; else if (book) continue;
            }
            boolean plain = ench.isEmpty();
            if (plain && ahCount.getOrDefault(id, 0) >= m.cap(id)) continue;
            int max = Math.max(1, it.getDefaultInstance().getMaxStackSize());
            int count = plain && max > 1 ? 1 + RND.nextInt(max) : 1;
            m.ah.botList(new Stack(id, count, ench), RND);
            if (plain) ahCount.merge(id, 1, Integer::sum);
            made++;
        }
    }

    void addOrders(int n) {
        List<Item> pool = m.pool;
        if (pool.isEmpty()) return;
        int made = 0, tries = 0;
        while (made < n && tries++ < n * 30) {
            Item it = pool.get(RND.nextInt(pool.size()));
            String id = m.idOf(it);
            boolean book = it == Items.ENCHANTED_BOOK;
            Map<String, Integer> ench = NONE;
            if (book || RND.nextInt(4) == 0) {
                Map<String, Integer> e = randomEnchants(it);
                if (e != null) ench = e; else if (book) continue;
            }
            boolean plain = ench.isEmpty();
            if (plain && orderCount.getOrDefault(id, 0) >= m.cap(id)) continue;
            int max = Math.max(1, it.getDefaultInstance().getMaxStackSize());
            int amt = !plain ? 1 + RND.nextInt(3) : max > 1 ? 16 + RND.nextInt(max * 3) : 1 + RND.nextInt(3);
            m.orders.botOrder(id, ench, amt, RND);
            if (plain) orderCount.merge(id, 1, Integer::sum);
            made++;
        }
    }

    /** Je Shard-Werkzeug/Spawner immer eine Bot-Order. */
    void ensureCustomOrders() {
        Set<String> have = new HashSet<>();
        for (Orders.Order o : m.orders.all()) if (o.owner == null && o.itemId.startsWith("donutmod:")) have.add(o.itemId);
        for (String cid : Custom.BASE.keySet())
            if (!have.contains(cid)) m.orders.botOrder(cid, NONE, 1 + RND.nextInt(2), RND);
    }

    // ---------- Takt ----------
    void tick(int t) {
        if (m.pool.isEmpty()) return;
        int batch = 5000;   // Aufbau verteilt auf viele Ticks
        long needAh = minAh - m.ah.size();
        if (needAh > 0) addAh((int) Math.min(batch, needAh));
        long needOrd = minOrders - m.orders.size();
        if (needOrd > 0) addOrders((int) Math.min(batch, needOrd));
        if (t % 20 == 0) churn();
    }

    /** Jede Sekunde: 7 alte Eintraege weg (von Bots gekauft/erfuellt), 10 neue dazu (bis zur Obergrenze). */
    void churn() {
        for (AuctionHouse.Listing l : m.ah.removeRandomBots(7, RND)) { ahRemoved(l); m.ahItems.remove(l.id); }
        for (Orders.Order o : m.orders.removeRandomBots(7, RND)) ordersRemoved(o);
        if (m.ah.size() < maxAh) addAh(10);
        if (m.orders.size() < maxOrders) addOrders(10);
    }

    // ---------- Sortier-Caches fuer die Menues ----------
    final Map<Integer, List<AuctionHouse.Listing>> ahCache = new HashMap<>();
    final Map<Integer, Long> ahCacheTime = new HashMap<>();
    List<Orders.Order> ordersCache;
    long ordersCacheTime = 0;

    /** Kopie der Angebote in der gewuenschten Reihenfolge (0 neueste, 1 guenstigste, 2 teuerste, 3 Name). */
    List<AuctionHouse.Listing> ahSorted(int mode) {
        if (mode == 0) {
            List<AuctionHouse.Listing> l = new ArrayList<>(m.ah.all());
            Collections.reverse(l);
            return l;
        }
        long now = System.currentTimeMillis();
        Long t = ahCacheTime.get(mode);
        if (t == null || now - t > 60_000) {
            List<AuctionHouse.Listing> l = new ArrayList<>(m.ah.all());
            switch (mode) {
                case 1 -> l.sort(Comparator.comparingLong((AuctionHouse.Listing x) -> x.price));
                case 2 -> l.sort(Comparator.comparingLong((AuctionHouse.Listing x) -> x.price).reversed());
                default -> l.sort(Comparator.comparing((AuctionHouse.Listing x) -> x.stack.id()));
            }
            ahCache.put(mode, l);
            ahCacheTime.put(mode, now);
        }
        return new ArrayList<>(ahCache.get(mode));
    }

    /** Orders nach Preis, hoechster zuerst (60 s gecacht). */
    List<Orders.Order> ordersSorted() {
        long now = System.currentTimeMillis();
        if (ordersCache == null || now - ordersCacheTime > 60_000) {
            List<Orders.Order> l = new ArrayList<>(m.orders.all());
            l.sort(Comparator.comparingLong((Orders.Order o) -> o.pricePerItem).reversed());
            ordersCache = l;
            ordersCacheTime = now;
        }
        return new ArrayList<>(ordersCache);
    }
}
