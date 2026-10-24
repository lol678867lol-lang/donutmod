package de.donutmod.fabric;

import de.donutmod.core.AuctionHouse;
import de.donutmod.core.Orders;
import de.donutmod.core.Stack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Kisten-Menues (Sell, Orders, Auktionshaus) - rein serverseitig, kein Client-Mod noetig. */
public final class Gui {
    private Gui() {}

    // ---------- Hilfen ----------
    static Component t(String s) { return Component.literal(s).withStyle(st -> st.withItalic(false)); }

    static ItemStack named(Item item, String name, String... lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, t(name));
        if (lore.length > 0) s.set(DataComponents.LORE, loreOf(lore));
        return s;
    }

    static ItemStack withLore(ItemStack base, String... lore) {
        ItemStack s = base.copy();
        s.set(DataComponents.LORE, loreOf(lore));
        return s;
    }

    static ItemLore loreOf(String... lore) {
        List<Component> l = new ArrayList<>();
        for (String x : lore) l.add(t(x));
        return new ItemLore(l);
    }

    static void say(ServerPlayer p, String s) { p.sendSystemMessage(Component.literal(s)); }

    static ItemStack pane() { return named(Items.GRAY_STAINED_GLASS_PANE, " "); }

    // ---------- Menue, das Klicks selbst behandelt ----------
    static class Menu extends ChestMenu {
        final boolean locked;
        final Set<Integer> special;
        final BiConsumer<Integer, ServerPlayer> handler;
        final Runnable after;
        final Consumer<ServerPlayer> onClose;

        Menu(int id, Inventory inv, Container box, boolean locked, Set<Integer> special,
             BiConsumer<Integer, ServerPlayer> handler, Runnable after, Consumer<ServerPlayer> onClose) {
            super(MenuType.GENERIC_9x6, id, inv, box, 6);
            this.locked = locked; this.special = special; this.handler = handler;
            this.after = after; this.onClose = onClose;
        }

        @Override
        public void clicked(int slot, int button, ClickType type, Player player) {
            boolean top = slot >= 0 && slot < 54;
            if (locked || (top && special.contains(slot))) {
                if (top && (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE))
                    handler.accept(slot, (ServerPlayer) player);
                sendAllDataToRemote();
                return;
            }
            super.clicked(slot, button, type, player);
            if (after != null) after.run();
        }

        @Override
        public ItemStack quickMoveStack(Player p, int i) {
            return locked ? ItemStack.EMPTY : super.quickMoveStack(p, i);
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            if (onClose != null) onClose.accept((ServerPlayer) player);
        }
    }

    // ---------- Seitenliste (AH, Orders) ----------
    static final class ListGui<T> {
        final DonutMod m; final ServerPlayer p; final String title;
        final Supplier<List<T>> source; final Function<T, ItemStack> display; final BiConsumer<T, ServerPlayer> onClick;
        final SimpleContainer box = new SimpleContainer(54);
        final Map<Integer, Supplier<ItemStack>> icons = new HashMap<>();
        final Map<Integer, Runnable> acts = new HashMap<>();
        int page = 0;
        List<T> view = List.of();

        ListGui(DonutMod m, ServerPlayer p, String title, Supplier<List<T>> source,
                Function<T, ItemStack> display, BiConsumer<T, ServerPlayer> onClick) {
            this.m = m; this.p = p; this.title = title; this.source = source; this.display = display; this.onClick = onClick;
        }

        void extra(int slot, Supplier<ItemStack> icon, Runnable act) { icons.put(slot, icon); acts.put(slot, act); }

        int pages() { return Math.max(1, (view.size() + 44) / 45); }

        void render() {
            view = source.get();
            page = Math.max(0, Math.min(page, pages() - 1));
            for (int i = 0; i < 45; i++) {
                int idx = page * 45 + i;
                box.setItem(i, idx < view.size() ? display.apply(view.get(idx)) : ItemStack.EMPTY);
            }
            for (int i = 45; i < 54; i++) box.setItem(i, pane());
            if (page > 0) box.setItem(45, named(Items.ARROW, "§eVorherige Seite", "§7Seite " + page + "/" + pages()));
            if (page < pages() - 1) box.setItem(53, named(Items.ARROW, "§eNächste Seite", "§7Seite " + (page + 2) + "/" + pages()));
            if (pages() > 10) {
                box.setItem(46, named(Items.SPECTRAL_ARROW, "§e-10 Seiten"));
                box.setItem(52, named(Items.SPECTRAL_ARROW, "§e+10 Seiten"));
            }
            box.setItem(49, named(Items.GOLD_INGOT, "§6Dein Geld: §a$" + DonutMod.fmt(m.eco.get(p.getUUID())),
                    "§7Seite " + (page + 1) + "/" + pages(), "§7Einträge: §f" + view.size()));
            for (Map.Entry<Integer, Supplier<ItemStack>> e : icons.entrySet()) box.setItem(e.getKey(), e.getValue().get());
        }

        void click(int slot, ServerPlayer pl) {
            if (slot < 45) {
                int idx = page * 45 + slot;
                if (idx < view.size()) onClick.accept(view.get(idx), pl);
            } else if (slot == 45 && page > 0) page--;
            else if (slot == 53 && page < pages() - 1) page++;
            else if (slot == 46 && pages() > 10) page = Math.max(0, page - 10);
            else if (slot == 52 && pages() > 10) page = Math.min(pages() - 1, page + 10);
            else if (acts.containsKey(slot)) acts.get(slot).run();
            render();
        }

        void open() {
            render();
            p.openMenu(new SimpleMenuProvider((id, inv, pl) ->
                    new Menu(id, inv, box, true, Set.<Integer>of(), (s, sp) -> click(s, sp), null, null),
                    Component.literal(title)));
        }
    }

    // ---------- Auktionshaus ----------
    static void openAh(DonutMod m, ServerPlayer p) {
        int[] st = {0, 0}; // [0] Sortierung, [1] 1 = nur meine Angebote
        String[] sortNames = {"Neueste zuerst", "Günstigste zuerst", "Teuerste zuerst", "Nach Item-Name"};
        ListGui<AuctionHouse.Listing> g = new ListGui<>(m, p, "Auktionshaus",
                () -> {
                    List<AuctionHouse.Listing> l = new ArrayList<>(m.ah.all());
                    if (st[1] == 1) l.removeIf(x -> !p.getUUID().equals(x.seller));
                    switch (st[0]) {
                        case 0 -> l.sort(Comparator.comparingInt((AuctionHouse.Listing x) -> x.id).reversed());
                        case 1 -> l.sort(Comparator.comparingLong((AuctionHouse.Listing x) -> x.price));
                        case 2 -> l.sort(Comparator.comparingLong((AuctionHouse.Listing x) -> x.price).reversed());
                        default -> l.sort(Comparator.comparing((AuctionHouse.Listing x) -> x.stack.id()));
                    }
                    return l;
                },
                l -> {
                    ItemStack base = m.ahItems.get(l.id);
                    if (base == null) return named(Items.BARRIER, "§cFehler");
                    boolean own = p.getUUID().equals(l.seller);
                    return withLore(base, "§7Preis: §a$" + DonutMod.fmt(l.price),
                            "§7Verkäufer: §f" + (l.seller == null ? "Server" : own ? "Du" : "Spieler"), "",
                            own ? "§eKlick: Angebot zurückholen" : "§aKlick: Sofort kaufen");
                },
                (l, pl) -> {
                    if (pl.getUUID().equals(l.seller)) {
                        if (m.ah.cancel(l)) {
                            ItemStack it = m.ahItems.remove(l.id);
                            if (it != null) pl.getInventory().placeItemBackInInventory(it);
                            say(pl, "§eAngebot zurückgeholt.");
                        }
                        return;
                    }
                    if (!m.ah.buy(pl.getUUID(), l)) { say(pl, "§cNicht genug Geld oder schon verkauft."); return; }
                    ItemStack it = m.ahItems.remove(l.id);
                    if (it != null) pl.getInventory().placeItemBackInInventory(it);
                    say(pl, "§aGekauft für $" + DonutMod.fmt(l.price));
                });
        g.extra(47, () -> named(Items.HOPPER, "§eSortierung", "§7Aktuell: §f" + sortNames[st[0]], "§7Klick: nächste Sortierung"),
                () -> st[0] = (st[0] + 1) % 4);
        g.extra(51, () -> st[1] == 0
                        ? named(Items.CHEST, "§bMeine Angebote", "§7Klick: nur deine Angebote zeigen")
                        : named(Items.BARRIER, "§cZurück", "§7Klick: alle Angebote zeigen"),
                () -> { st[1] = 1 - st[1]; g.page = 0; });
        g.open();
    }

    // ---------- Orders ----------
    static void openOrders(DonutMod m, ServerPlayer p) {
        boolean[] mine = {false};
        ListGui<Orders.Order> g = new ListGui<>(m, p, "Orders",
                () -> {
                    List<Orders.Order> l = new ArrayList<>(m.orders.all());
                    if (mine[0]) l.removeIf(o -> !p.getUUID().equals(o.owner));
                    l.sort(Comparator.comparingLong((Orders.Order o) -> o.pricePerItem).reversed());
                    return l;
                },
                o -> {
                    Item it = DonutMod.ITEM_BY_ID.get(o.itemId);
                    if (it == null) return named(Items.BARRIER, "§cUnbekanntes Item");
                    boolean own = p.getUUID().equals(o.owner);
                    int max = Math.max(1, it.getDefaultInstance().getMaxStackSize());
                    return withLore(new ItemStack(it, Math.max(1, Math.min(o.remaining, max))),
                            "§7Zahlt: §a$" + DonutMod.fmt(o.pricePerItem) + " §7pro Stück",
                            "§7Gesucht: §f" + o.remaining + " Stück",
                            "§7Gesamt: §a$" + DonutMod.fmt(o.pricePerItem * o.remaining),
                            "§7Besteller: §f" + (o.owner == null ? "Server" : own ? "Du" : "Spieler"), "",
                            own ? "§eKlick: Order abbrechen (Geld zurück)" : "§aKlick: passende Items aus deinem Inventar liefern");
                },
                (o, pl) -> deliver(m, pl, o));
        g.extra(47, () -> mine[0]
                        ? named(Items.BARRIER, "§cAlle Orders", "§7Klick: alle Orders zeigen")
                        : named(Items.CHEST, "§bMeine Orders", "§7Klick: nur deine Orders zeigen", "",
                                "§7Neue Order: §f/orders create <Menge> <Preis>", "§7(Item in der Hand, Preis pro Stück)"),
                () -> { mine[0] = !mine[0]; g.page = 0; });
        g.extra(51, () -> named(Items.ENDER_CHEST, "§dAbholen",
                        "§7Gelieferte Items: §f" + m.pendingCount(p.getUUID()), "§7Klick: ins Inventar holen"),
                () -> m.claim(p));
        g.open();
    }

    static void deliver(DonutMod m, ServerPlayer pl, Orders.Order o) {
        if (pl.getUUID().equals(o.owner)) {
            long back = o.pricePerItem * o.remaining;
            if (m.orders.cancel(o)) say(pl, "§eOrder abgebrochen, $" + DonutMod.fmt(back) + " zurück.");
            return;
        }
        Item it = DonutMod.ITEM_BY_ID.get(o.itemId);
        if (it == null) return;
        if (!o.enchants.isEmpty()) { say(pl, "§cDiese Order braucht verzauberte Items."); return; }
        Inventory inv = pl.getInventory();
        int need = o.remaining, got = 0;
        for (int i = 0; i < 36 && got < need; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == it) {
                int take = Math.min(s.getCount(), need - got);
                s.shrink(take);
                got += take;
                if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            }
        }
        if (got == 0) { say(pl, "§cDu hast dieses Item nicht im Inventar."); return; }
        UUID owner = o.owner;
        long pay = m.orders.fill(o, new Stack(o.itemId, got), pl.getUUID());
        if (owner != null) m.addPending(owner, new ItemStack(it, got));
        say(pl, "§aGeliefert: " + got + "x für $" + DonutMod.fmt(pay));
    }

    // ---------- Sell ----------
    static void openSell(DonutMod m, ServerPlayer p) {
        SimpleContainer box = new SimpleContainer(54);
        Runnable refresh = () -> box.setItem(53, sellButton(m, box));
        refresh.run();
        p.openMenu(new SimpleMenuProvider((id, inv, pl) ->
                new Menu(id, inv, box, false, Set.of(53),
                        (slot, sp) -> { sellAll(m, box, sp); sp.closeContainer(); },
                        refresh, sp -> sellAll(m, box, sp)),
                Component.literal("Sell - Items reinlegen")));
    }

    static List<Stack> collect(SimpleContainer box) {
        List<Stack> list = new ArrayList<>();
        for (int i = 0; i < 53; i++) {
            ItemStack s = box.getItem(i);
            if (!s.isEmpty()) list.add(DonutMod.toStack(s));
        }
        return list;
    }

    static ItemStack sellButton(DonutMod m, SimpleContainer box) {
        List<Stack> list = collect(box);
        long v = list.isEmpty() ? 0 : m.sell.preview(list);
        return named(Items.LIME_STAINED_GLASS_PANE, "§aVerkaufen: $" + DonutMod.fmt(v),
                "§7Klick hier oder schließe das Menü.");
    }

    static void sellAll(DonutMod m, SimpleContainer box, ServerPlayer p) {
        List<Stack> list = collect(box);
        for (int i = 0; i < 53; i++) box.setItem(i, ItemStack.EMPTY);
        if (list.isEmpty()) return;
        long pay = m.sell.sell(p.getUUID(), list).payout();
        say(p, "§aVerkauft für $" + DonutMod.fmt(pay) + " §7| Kontostand: $" + DonutMod.fmt(m.eco.get(p.getUUID())));
    }
}
