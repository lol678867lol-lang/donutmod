package de.donutmod.core;

import java.util.*;

public class AuctionHouse {
    public static final class Listing {
        public final int id; public final UUID seller; // null -> Bot
        public final Stack stack; public final long price;
        Listing(int id, UUID seller, Stack stack, long price) { this.id = id; this.seller = seller; this.stack = stack; this.price = price; }
    }

    private final PriceBook prices; private final MetaManager meta; private final Economy eco;
    private final List<Listing> listings = new ArrayList<>();
    private int nextId = 1;

    public AuctionHouse(PriceBook p, MetaManager m, Economy e) { prices = p; meta = m; eco = e; }
    public List<Listing> all() { return Collections.unmodifiableList(listings); }

    public Listing list(UUID seller, Stack s, long price) {
        Listing l = new Listing(nextId++, seller, s, price);
        listings.add(l);
        return l;
    }

    public Listing botList(Stack s, Random rnd) {
        double f = 0.6 + rnd.nextDouble() * 0.7;
        f *= 1.0 + meta.bonus(s.id());
        return list(null, s, Math.round(prices.stackPrice(s) * f));
    }

    /** Kaufchance pro Zyklus: günstig = hoch, teuer = fast 0, Meta = Boost. */
    public double buyChance(Listing l) {
        double market = prices.stackPrice(l.stack);
        double ratio = l.price / Math.max(1.0, market);
        double bonus = meta.bonus(l.stack.id());
        double demand = 1.0 + 2.0 * bonus;   // mehr Nachfrage bei Meta
        ratio /= 1.0 + bonus;                // Meta-Items vertragen höhere Preise
        return Math.min(0.9, 0.05 * demand * Math.pow(1.0 / ratio, 4));
    }

    /** Ein Bot-Kauf-Zyklus; Spieler-Angebote zahlen den Verkäufer aus. */
    public List<Listing> tickBotBuyers(Random rnd) {
        List<Listing> sold = new ArrayList<>();
        for (Listing l : new ArrayList<>(listings))
            if (rnd.nextDouble() < buyChance(l)) {
                if (l.seller != null) eco.add(l.seller, l.price);
                listings.remove(l);
                sold.add(l);
            }
        return sold;
    }

    /** Spieler kauft. */
    public boolean buy(UUID buyer, Listing l) {
        if (!listings.contains(l) || !eco.take(buyer, l.price)) return false;
        if (l.seller != null) eco.add(l.seller, l.price);
        listings.remove(l);
        return true;
    }
    /** Angebot zurueckziehen (Verkaeufer holt sein Item zurueck). */
    public boolean cancel(Listing l) { return listings.remove(l); }
}
