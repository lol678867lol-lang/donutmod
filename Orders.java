package de.donutmod.core;

import java.util.*;

public class Orders {
    public static final class Order {
        public final int id; public final UUID owner; // owner == null -> Bot
        public final String itemId; public final Map<String, Integer> enchants;
        public int remaining; public final long pricePerItem;
        Order(int id, UUID owner, String itemId, Map<String, Integer> ench, int amount, long ppi) {
            this.id = id; this.owner = owner; this.itemId = itemId; this.enchants = ench;
            this.remaining = amount; this.pricePerItem = ppi;
        }
    }

    private final PriceBook prices; private final MetaManager meta; private final Economy eco;
    private final List<Order> orders = new ArrayList<>();
    private final Map<String, List<Order>> byItem = new HashMap<>();
    private int nextId = 1;

    public Orders(PriceBook p, MetaManager m, Economy e) { prices = p; meta = m; eco = e; }
    public List<Order> all() { return Collections.unmodifiableList(orders); }
    public int size() { return orders.size(); }

    private void add(Order o) {
        orders.add(o);
        byItem.computeIfAbsent(o.itemId, k -> new ArrayList<>()).add(o);
    }

    private boolean remove(Order o) {
        boolean r = orders.remove(o);
        List<Order> l = byItem.get(o.itemId);
        if (l != null) { l.remove(o); if (l.isEmpty()) byItem.remove(o.itemId); }
        return r;
    }

    /** Entfernt bis zu n zufaellige Bot-Orders (Bots haben sie erfuellt). */
    public List<Order> removeRandomBots(int n, Random rnd) {
        List<Order> out = new ArrayList<>();
        int tries = 0;
        while (out.size() < n && !orders.isEmpty() && tries++ < n * 20) {
            Order o = orders.get(rnd.nextInt(orders.size()));
            if (o.owner == null) { remove(o); out.add(o); }
        }
        return out;
    }

    /** Bot-Order: Preis 1.0-1.5x Sell-Preis, bei Meta nur runter (Faktor). */
    public Order botOrder(String itemId, Map<String, Integer> ench, int amount, Random rnd) {
        double f = 1.0 + rnd.nextDouble() * 0.5;
        if (meta.isMeta(itemId)) f *= meta.orderPriceFactor;
        long ppi = Math.round(prices.sellUnitPrice(itemId, ench) * f);
        Order o = new Order(nextId++, null, itemId, ench, amount, ppi);
        add(o);
        return o;
    }

    /** Spieler-Order: Preis wird sofort bezahlt. Lieferung durch Bots später (deliverDue). */
    public Order playerOrder(UUID owner, String itemId, Map<String, Integer> ench, int amount, long ppi) {
        if (!eco.take(owner, ppi * amount)) return null;
        Order o = new Order(nextId++, owner, itemId, ench, amount, ppi);
        add(o);
        return o;
    }

    private boolean matches(Order o, Stack s) {
        if (!o.itemId.equals(s.id())) return false;
        for (var e : o.enchants.entrySet())
            if (s.enchants().getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        return true;
    }

    /** Beste passende Order, aber nur wenn Order-Preis > normaler Sell-Preis. */
    public Order bestFor(Stack s) {
        long sell = prices.sellUnitPrice(s);
        Order best = null;
        for (Order o : byItem.getOrDefault(s.id(), List.of()))
            if (o.owner == null && o.remaining > 0 && matches(o, s) && o.pricePerItem > sell
                    && (best == null || o.pricePerItem > best.pricePerItem)) best = o;
        return best;
    }

    /** Liefert in die Order. Rückgabe: ausgezahlter Betrag; Rest-Items bleiben beim Aufrufer. */
    public long fill(Order o, Stack s, UUID seller) {
        int n = Math.min(o.remaining, s.count());
        o.remaining -= n;
        long pay = o.pricePerItem * n;
        eco.add(seller, pay);
        if (o.remaining <= 0) remove(o);
        return pay;
    }

    /** Anzeige-Wert fürs Tooltip: höhere Order-Preise ersetzen den Sell-Preis. */
    public long tooltipUnitValue(Stack s) {
        Order o = bestFor(s);
        return o != null ? o.pricePerItem : prices.sellUnitPrice(s);
    }
    /** Spieler-Order abbrechen: Restbetrag wird zurueckgezahlt. */
    public boolean cancel(Order o) {
        if (!remove(o)) return false;
        if (o.owner != null) eco.add(o.owner, o.pricePerItem * o.remaining);
        return true;
    }
    /** Bots liefern n Items in eine Spieler-Order (Geld lag schon im Escrow). Rueckgabe: wirklich geliefert. */
    public int botDeliver(Order o, int n) {
        int d = Math.min(n, o.remaining);
        o.remaining -= d;
        if (o.remaining <= 0) remove(o);
        return d;
    }
    /** Gespeicherte Spieler-Order wiederherstellen (kostet kein Geld, das Geld war schon im Escrow). */
    public Order restore(UUID owner, String itemId, Map<String, Integer> ench, int remaining, long ppi) {
        Order o = new Order(nextId++, owner, itemId, ench, remaining, ppi);
        add(o);
        return o;
    }
}
