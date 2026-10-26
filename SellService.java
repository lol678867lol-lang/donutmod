package de.donutmod.core;

import java.util.*;

/** Logik hinter dem /sell-GUI: erst passende Orders (nur wenn teurer), sonst fester Sell-Preis. */
public class SellService {
    public record Result(long payout, List<Stack> returned) {}

    private final PriceBook prices; private final Orders orders; private final Economy eco;
    public SellService(PriceBook p, Orders o, Economy e) { prices = p; orders = o; eco = e; }

    /** Für den Sell-Button: Gesamtpreis live berechnen, ohne etwas zu verändern. */
    public long preview(List<Stack> items) {
        long total = 0;
        for (Stack s : items) {
            Orders.Order o = orders.bestFor(s);
            if (o != null) {
                int n = Math.min(o.remaining, s.count());
                total += o.pricePerItem * n + prices.sellUnitPrice(s) * (s.count() - n);
            } else total += prices.sellStackPrice(s);
        }
        return total;
    }

    public Result sell(UUID seller, List<Stack> items) {
        long total = 0;
        for (Stack s : items) {
            prices.bump(s.id(), -0.0004 * s.count());   // wird viel verkauft -> guenstiger
            Stack rest = s;
            Orders.Order o;
            while (rest.count() > 0 && (o = orders.bestFor(rest)) != null) {
                int n = Math.min(o.remaining, rest.count());
                total += orders.fill(o, rest, seller);
                rest = new Stack(rest.id(), rest.count() - n, rest.enchants());
            }
            if (rest.count() > 0) {
                long p = prices.sellStackPrice(rest);
                eco.add(seller, p);
                total += p;
            }
        }
        return new Result(total, List.of());
    }
}
