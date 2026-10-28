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
    /** Beliebtheit je Item: steigt bei Kaeufen, sinkt mit der Zeit. Unbeliebte Items haben nur 1 Angebot. */
    final Map<String, Double> pop = new HashMap<>();
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
        if (Perf.LITE) { minAh = Math.max(10_000L, minAh * 3 / 10); minOrders = Math.max(5_000L, minOrders * 3 / 10); }
        maxAh = (long) (minAh * capFactor);
        maxOrders = (long) (minOrders * capFactor);
        reduced = scale < 0.999 || Perf.LITE;
        System.out.println("[DonutMod] Markt-Ziel: " + minAh + " Angebote, " + minOrders + " Orders (RAM-Faktor " + String.format("%.2f", scale) + ")");
        for (Item it : m.pool) pop.put(m.idOf(it), 1.0);
        for (AuctionHouse.Listing l : m.ah.all()) if (l.stack.enchants().isEmpty()) ahCount.merge(l.stack.id(), 1, Integer::sum);
        for (Orders.Order o : m.orders.all()) if (o.enchants.isEmpty()) orderCount.merge(o.itemId, 1, Integer::sum);
    }

    // ---------- Beliebtheit ----------
    void onBought(String id, double w) { pop.merge(id, w, (a, b) -> Math.min(5.0, a + b)); }

    /** Jede Minute: Beliebtheit sinkt langsam Richtung 0,3. */
    void popDecay() { pop.replaceAll((k, v) -> 0.3 + (v - 0.3) * 0.98); }

    /** Wie viele Angebote ein Item gleichzeitig haben darf: beliebte viele, unbeliebte nur 1. */
    int capFor(String id) {
        double s = pop.getOrDefault(id, 1.0);
        int rarity = m.cap(id);
        if (s < 0.6) return 1;
        long price = Math.max(1, m.prices.unitPrice(id, NONE));
        long c = (long) Math.max(2, Math.min(200_000.0, 3e7 / price * Math.min(s, 5.0)));
        return (int) Math.min(rarity, c);
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
                for (Holder.Reference<Enchantment> h : m.enchList)
                    if (!h.getRegisteredName().contains("curse") && (book || h.value().isSupportedItem(st))) r.add(h);
            return r;
        });
        if (ok.isEmpty()) return null;
        boolean book = it == Items.ENCHANTED_BOOK;
        List<Holder.Reference<Enchantment>> chosen = new ArrayList<>();
        if (!book)
            for (String pref : new String[]{"minecraft:mending", "minecraft:unbreaking"})
                if (RND.nextInt(10) < 8)
                    for (Holder.Reference<Enchantment> h : ok) if (h.getRegisteredName().equals(pref)) { tryAdd(chosen, h); break; }
        List<Holder.Reference<Enchantment>> shuffled = new ArrayList<>(ok);
        Collections.shuffle(shuffled, RND);
        int target = book ? 1 + (RND.nextInt(4) == 0 ? 1 : 0) : 3 + RND.nextInt(3);
        for (Holder.Reference<Enchantment> h : shuffled) {
            if (chosen.size() >= target) break;
            tryAdd(chosen, h);
        }
        Map<String, Integer> out = new HashMap<>();
        for (Holder.Reference<Enchantment> h : chosen) {
            int max = Math.max(1, h.value().getMaxLevel());
            out.put(h.getRegisteredName(), RND.nextInt(4) != 0 ? max : 1 + RND.nextInt(max));
        }
        return out;
    }

    /** Fuegt eine Verzauberung nur hinzu, wenn sie mit allen bisherigen vertraeglich ist (kein Protection + Fire Protection). */
    static void tryAdd(List<Holder.Reference<Enchantment>> chosen, Holder.Reference<Enchantment> h) {
        for (Holder.Reference<Enchantment> c : chosen)
            if (c == h || !Enchantment.areCompatible(h, c)) return;
        chosen.add(h);
    }

    /** Bot-Orders: guenstige Items werden in riesigen Mengen geordert (z.B. 10 Mio. Kelp). */
    int botAmount(Item it, String id, boolean plain) {
        if (!plain) return 1 + RND.nextInt(3);
        long p = Math.max(1, m.prices.sellUnitPrice(id, NONE));
        boolean stack = Math.max(1, it.getDefaultInstance().getMaxStackSize()) > 1;
        long cap = Math.min(stack ? 50_000_000L : 200L, Math.max(stack ? 1000L : 3L, (long) (5e10 / p)));
        double lo = Math.log(stack ? 256 : 1), hi = Math.log(Math.max(cap, 2));
        long amt = (long) Math.exp(lo + RND.nextDouble() * (hi - lo));
        return (int) Math.max(1, Math.min(cap, amt));
    }

    /** Zufaellige, aber runde Stackgroessen: 1, 16, 32 oder 64 (nie mehr als der Stack des Items erlaubt). */
    static int stackSize(int max) {
        int v = RND.nextInt(100);
        int size = v < 15 ? 1 : v < 35 ? 16 : v < 60 ? 32 : 64;
        return Math.min(size, max);
    }

    void addAh(int n) {
        List<Item> pool = m.pool;
        if (pool.isEmpty()) return;
        int made = 0, tries = 0;
        while (made < n && tries++ < n * 30) {
            if (RND.nextInt(100) < 4) {   // ca. 4 % Traenke
                m.ah.botList(new Stack(Custom.POTION_IDS.get(RND.nextInt(Custom.POTION_IDS.size())), 1, NONE), RND);
                made++;
                continue;
            }
            Item it = pool.get(RND.nextInt(pool.size()));
            String id = m.idOf(it);
            boolean book = it == Items.ENCHANTED_BOOK;
            Map<String, Integer> ench = NONE;
            if (book || RND.nextInt(4) == 0) {
                Map<String, Integer> e = randomEnchants(it);
                if (e != null) ench = e; else if (book) continue;
            }
            boolean plain = ench.isEmpty();
            if (plain && ahCount.getOrDefault(id, 0) >= capFor(id)) continue;
            int max = Math.max(1, it.getDefaultInstance().getMaxStackSize());
            int count = plain && max > 1 ? stackSize(max) : 1;
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
            if (RND.nextInt(100) < 4) {
                m.orders.botOrder(Custom.POTION_IDS.get(RND.nextInt(Custom.POTION_IDS.size())), NONE, 8 + RND.nextInt(193), RND);
                made++;
                continue;
            }
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
            int amt = botAmount(it, id, plain);
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
        // Aufbau nur mit begrenzter Rechenzeit pro Tick (auf schwachen PCs weniger), damit nichts ruckelt
        long end = System.nanoTime() + Perf.BUDGET_NS;
        while (System.nanoTime() < end) {
            boolean did = false;
            if (m.ah.size() < minAh) { addAh(100); did = true; }
            if (m.orders.size() < minOrders) { addOrders(100); did = true; }
            if (!did) break;
        }
        if (t % 20 == 0) churn();
    }

    /** Jede Sekunde: 7 alte Eintraege weg (von Bots gekauft/erfuellt), 10 neue dazu (bis zur Obergrenze). */
    void churn() {
        for (AuctionHouse.Listing l : m.ah.removeRandomBots(7, RND)) { ahRemoved(l); m.ahItems.remove(l.id); onBought(l.stack.id(), 0.02); }
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
