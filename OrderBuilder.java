package de.donutmod.fabric;

import de.donutmod.core.Orders;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Menue zum Erstellen von Orders: Item im Chat suchen, Verzauberungen, Menge und Preis per Klick einstellen. */
public final class OrderBuilder {
    private OrderBuilder() {}

    static final Set<UUID> AWAIT = new HashSet<>();

    static void register(DonutMod m) {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (!AWAIT.remove(sender.getUUID())) return true;
            String q = message.signedContent().trim();
            m.srv.execute(() -> results(m, sender, q));
            return false;
        });
    }

    static void start(DonutMod m, ServerPlayer p) {
        AWAIT.add(p.getUUID());
        p.closeContainer();
        Gui.say(p, "§eTippe jetzt den Namen des Items in den Chat (z.B. diamond, tnt, shulker). 'abbrechen' bricht ab.");
    }

    static void results(DonutMod m, ServerPlayer p, String q) {
        String ql = q.toLowerCase(Locale.ROOT);
        if (ql.equals("abbrechen")) { Gui.openOrders(m, p); return; }
        List<String> ids = new ArrayList<>();
        for (Item it : m.pool) {
            String id = m.idOf(it);
            if (Gui.matches(ql, id, Map.of(), it)) ids.add(id);
        }
        for (String cid : Custom.BASE.keySet()) if (Gui.matches(ql, cid, Map.of(), null)) ids.add(cid);
        if (ids.isEmpty()) { Gui.say(p, "§cKein Item gefunden für: " + q); Gui.openOrders(m, p); return; }
        Gui.ListGui<String> g = new Gui.ListGui<>(m, p, "Item wählen: " + q,
                () -> ids,
                id -> Gui.withLore(m.buildStack(id, Map.of(), 1), "§aKlick: auswählen"),
                (id, pl) -> builder(m, pl, id));
        g.extra(52, () -> Gui.named(Items.BARRIER, "§cAbbrechen"), () -> Gui.openOrders(m, p));
        g.open();
    }

    static final class Draft {
        final String id; long amount = 64; long price = 1000; int epage = 0;
        final Map<String, Integer> ench = new LinkedHashMap<>();
        final List<Holder.Reference<Enchantment>> avail = new ArrayList<>();
        Draft(String id) { this.id = id; }
    }

    static String pretty(String regName) { return Custom.pretty(regName); }

    static void builder(DonutMod m, ServerPlayer p, String id) {
        Draft d = new Draft(id);
        ItemStack proto = m.buildStack(id, Map.of(), 1);
        d.price = Math.max(1, Math.round(m.prices.sellUnitPrice(id, Map.of()) * 1.2));
        boolean book = id.equals("minecraft:enchanted_book");
        if (!proto.isEmpty())
            for (Holder.Reference<Enchantment> h : m.enchList)
                if (book || (proto.isEnchantable() && h.value().isSupportedItem(proto))) d.avail.add(h);
        SimpleContainer box = new SimpleContainer(54);
        render(m, d, box);
        p.openMenu(new SimpleMenuProvider((cid, inv, pl) ->
                new Gui.Menu(cid, inv, box, true, Set.<Integer>of(), (slot, sp) -> { click(m, d, sp, slot); render(m, d, box); }, null, null),
                Component.literal("Neue Order")));
    }

    static void render(DonutMod m, Draft d, SimpleContainer box) {
        for (int i = 0; i < 54; i++) box.setItem(i, Gui.pane());
        List<String> el = new ArrayList<>();
        for (Map.Entry<String, Integer> e : d.ench.entrySet()) el.add("§7" + pretty(e.getKey()) + " " + e.getValue());
        box.setItem(4, Gui.withLore(m.buildStack(d.id, d.ench, 1), el.isEmpty() ? new String[]{"§7Keine Verzauberungen"} : el.toArray(new String[0])));
        int[] aSlots = {10, 11, 12, 14, 15, 16};
        String[] aNames = {"-64", "-10", "-1", "+1", "+10", "+64"};
        for (int i = 0; i < 6; i++) box.setItem(aSlots[i], Gui.named(i < 3 ? Items.RED_STAINED_GLASS_PANE : Items.LIME_STAINED_GLASS_PANE, "§eMenge " + aNames[i]));
        box.setItem(9, Gui.named(Items.RED_STAINED_GLASS_PANE, "§eMenge -1000"));
        box.setItem(17, Gui.named(Items.LIME_STAINED_GLASS_PANE, "§eMenge +1000"));
        box.setItem(0, Gui.named(Items.CLOCK, "§eMenge ÷ 10"));
        box.setItem(8, Gui.named(Items.NETHER_STAR, "§eMenge × 10", "§7Bis 1 Milliarde möglich"));
        box.setItem(13, Gui.named(Items.PAPER, "§fMenge: §a" + DonutMod.fmt(d.amount)));
        int[] pSlots = {19, 20, 21, 23, 24, 25};
        String[] pNames = {"-1M", "-100k", "-10k", "+10k", "+100k", "+1M"};
        for (int i = 0; i < 6; i++) box.setItem(pSlots[i], Gui.named(i < 3 ? Items.RED_STAINED_GLASS_PANE : Items.LIME_STAINED_GLASS_PANE, "§6Preis " + pNames[i]));
        box.setItem(22, Gui.named(Items.GOLD_INGOT, "§fPreis pro Stück: §a$" + DonutMod.fmt(d.price)));
        box.setItem(18, Gui.named(Items.CLOCK, "§eMarktpreis setzen", "§7Setzt den Preis auf ca. den Sell-Preis +20 %"));
        box.setItem(26, Gui.named(Items.NETHER_STAR, "§eMal 2", "§7Verdoppelt den Preis"));
        for (int i = 0; i < 17; i++) {
            int idx = d.epage * 17 + i;
            if (idx >= d.avail.size()) break;
            Holder.Reference<Enchantment> h = d.avail.get(idx);
            int lvl = d.ench.getOrDefault(h.getRegisteredName(), 0);
            ItemStack b = Gui.named(Items.ENCHANTED_BOOK, (lvl > 0 ? "§a" : "§7") + pretty(h.getRegisteredName()) + (lvl > 0 ? " " + lvl : ""),
                    "§7Max. Stufe: " + h.value().getMaxLevel(), "§eKlick: Stufe erhöhen");
            if (lvl > 0) b.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            box.setItem(27 + i, b);
        }
        if (d.avail.size() > 17) box.setItem(44, Gui.named(Items.ARROW, "§eWeitere Verzauberungen", "§7Seite " + (d.epage + 1) + "/" + ((d.avail.size() + 16) / 17)));
        box.setItem(45, Gui.named(Items.BARRIER, "§cAbbrechen"));
        box.setItem(49, Gui.named(Items.GOLD_BLOCK, "§6Gesamt: §a$" + DonutMod.fmt(d.price * d.amount),
                "§7Wird dir beim Erstellen abgezogen", "§7(Rückzahlung bei Abbruch)"));
        box.setItem(53, Gui.named(Items.LIME_CONCRETE, "§aOrder erstellen"));
    }

    static void click(DonutMod m, Draft d, ServerPlayer p, int slot) {
        int[] aSlots = {10, 11, 12, 14, 15, 16};
        int[] aDelta = {-64, -10, -1, 1, 10, 64};
        for (int i = 0; i < 6; i++) if (slot == aSlots[i]) d.amount = Math.max(1, Math.min(1_000_000_000, d.amount + aDelta[i]));
        if (slot == 9) d.amount = Math.max(1, d.amount - 1000);
        if (slot == 17) d.amount = Math.min(1_000_000_000L, d.amount + 1000);
        if (slot == 0) d.amount = Math.max(1, d.amount / 10);
        if (slot == 8) d.amount = Math.min(1_000_000_000L, d.amount * 10);
        int[] pSlots = {19, 20, 21, 23, 24, 25};
        long[] pDelta = {-1_000_000, -100_000, -10_000, 10_000, 100_000, 1_000_000};
        for (int i = 0; i < 6; i++) if (slot == pSlots[i]) d.price = Math.max(1, d.price + pDelta[i]);
        if (slot == 18) d.price = Math.max(1, Math.round(m.prices.sellUnitPrice(d.id, d.ench) * 1.2));
        if (slot == 26) d.price = Math.min(1_000_000_000_000L, d.price * 2);
        if (slot >= 27 && slot <= 43) {
            int idx = d.epage * 17 + (slot - 27);
            if (idx < d.avail.size()) {
                Holder.Reference<Enchantment> h = d.avail.get(idx);
                String name = h.getRegisteredName();
                int next = d.ench.getOrDefault(name, 0) + 1;
                if (next > Math.max(1, h.value().getMaxLevel())) d.ench.remove(name); else d.ench.put(name, next);
            }
        }
        if (slot == 44 && d.avail.size() > 17) d.epage = (d.epage + 1) % ((d.avail.size() + 16) / 17);
        if (slot == 45) Gui.openOrders(m, p);
        if (slot == 53) {
            long total = d.price * d.amount;
            Orders.Order o = m.orders.playerOrder(p.getUUID(), d.id, new HashMap<>(d.ench), (int) d.amount, d.price);
            if (o == null) { Gui.say(p, "§cNicht genug Geld. Du brauchst $" + DonutMod.fmt(total) + "."); return; }
            DonutMod.ping(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f);
            Gui.say(p, "§aOrder #" + o.id + " erstellt (bezahlt: $" + DonutMod.fmt(total) + ").");
            Gui.openOrders(m, p);
        }
    }
}
