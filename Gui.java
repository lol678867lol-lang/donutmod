package de.donutmod.fabric;

import de.donutmod.core.AuctionHouse;
import de.donutmod.core.Orders;
import de.donutmod.core.Stack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
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
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Kisten-Menues (Sell, Orders, Auktionshaus, Lager) - rein serverseitig, kein Client-Mod noetig. */
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

    /** Geparste Suche: Woerter (Item/Verzauberung) und Stufen (5, V). */
    static final class Query { final List<String> words = new ArrayList<>(); final List<Integer> levels = new ArrayList<>(); }

    static final Map<String, String> ALIAS = Map.ofEntries(
            Map.entry("buch", "book"), Map.entry("buecher", "book"), Map.entry("bücher", "book"),
            Map.entry("spitzhacke", "pickaxe"), Map.entry("pickaxt", "pickaxe"), Map.entry("schwert", "sword"),
            Map.entry("axt", "axe"), Map.entry("schaufel", "shovel"), Map.entry("hacke", "hoe"),
            Map.entry("helm", "helmet"), Map.entry("brustplatte", "chestplate"), Map.entry("hose", "leggings"),
            Map.entry("stiefel", "boots"), Map.entry("schild", "shield"), Map.entry("dia", "diamond"),
            Map.entry("diamant", "diamond"), Map.entry("eisen", "iron"),
            Map.entry("speed", "swiftness"), Map.entry("pot", "potion"), Map.entry("trank", "potion"));

    static String lastQ = null;
    static Query lastP = null;

    static Integer levelOf(String t) {
        if (t.matches("\\d{1,2}")) return Integer.parseInt(t);
        return switch (t) {
            case "i" -> 1; case "ii" -> 2; case "iii" -> 3; case "iv" -> 4; case "v" -> 5;
            case "vi" -> 6; case "vii" -> 7; case "viii" -> 8; case "ix" -> 9; case "x" -> 10;
            default -> null;
        };
    }

    static Query parse(String q) {
        Query r = new Query();
        for (String t : q.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (t.isEmpty()) continue;
            t = ALIAS.getOrDefault(t, t);
            Integer lvl = levelOf(t);
            if (lvl != null) r.levels.add(lvl); else r.words.add(t);
        }
        return r;
    }

    /** Suche: "effi 5 book" findet ein Effizienz-V-Buch, "shard pick" die Shard-Spitzhacke, "tnt" alles mit TNT. */
    static boolean matches(String q, String id, Map<String, Integer> ench, Item item) {
        if (q.isEmpty()) return true;
        if (!q.equals(lastQ)) { lastQ = q; lastP = parse(q); }
        Query p = lastP;
        String path = id.substring(id.indexOf(':') + 1);
        String name = null;
        for (String w : p.words) {
            if (path.contains(w)) continue;
            boolean hit = false;
            for (String e : ench.keySet()) if (e.contains(w)) { hit = true; break; }
            if (!hit && item != null) {
                if (name == null) name = DonutMod.nameOf(item).replace(' ', '_');
                hit = name.contains(w);
            }
            if (!hit) return false;
        }
        for (int lvl : p.levels) {
            boolean ok = false;
            for (Map.Entry<String, Integer> e : ench.entrySet()) {
                if (e.getValue() != lvl) continue;
                if (p.words.isEmpty()) { ok = true; break; }
                for (String w : p.words) if (e.getKey().contains(w)) { ok = true; break; }
                if (ok) break;
            }
            if (!ok) return false;
        }
        return true;
    }

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
                if (player.containerMenu == this) sendAllDataToRemote();
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

    // ---------- Seitenliste ----------
    static final class ListGui<T> {
        final DonutMod m; final ServerPlayer p; final String title;
        final Supplier<List<T>> source; final Function<T, ItemStack> display; final BiConsumer<T, ServerPlayer> onClick;
        final SimpleContainer box = new SimpleContainer(54);
        final Map<Integer, Supplier<ItemStack>> icons = new HashMap<>();
        final Map<Integer, Runnable> acts = new HashMap<>();
        int page = 0;
        List<T> view = List.of();
        /** Wenn gesetzt: Liste wird nach einem Item-Klick nicht neu geladen, nur das Element entfernt, falls es weg ist. */
        Predicate<T> alive;

        ListGui(DonutMod m, ServerPlayer p, String title, Supplier<List<T>> source,
                Function<T, ItemStack> display, BiConsumer<T, ServerPlayer> onClick) {
            this.m = m; this.p = p; this.title = title; this.source = source; this.display = display; this.onClick = onClick;
        }

        void extra(int slot, Supplier<ItemStack> icon, Runnable act) { icons.put(slot, icon); acts.put(slot, act); }

        int pages() { return Math.max(1, (view.size() + 44) / 45); }
        int jump() { return Math.max(10, pages() / 20); }

        List<T> pageItems() {
            int from = Math.min(view.size(), page * 45);
            return new ArrayList<>(view.subList(from, Math.min(view.size(), from + 45)));
        }

        void load() { view = source.get(); }

        void render() {
            page = Math.max(0, Math.min(page, pages() - 1));
            for (int i = 0; i < 45; i++) {
                int idx = page * 45 + i;
                box.setItem(i, idx < view.size() ? display.apply(view.get(idx)) : ItemStack.EMPTY);
            }
            for (int i = 45; i < 54; i++) box.setItem(i, pane());
            if (page > 0) box.setItem(45, named(Items.ARROW, "§eVorherige Seite", "§7Seite " + page + "/" + pages()));
            if (page < pages() - 1) box.setItem(53, named(Items.ARROW, "§eNächste Seite", "§7Seite " + (page + 2) + "/" + pages()));
            if (pages() > 10) {
                box.setItem(46, named(Items.SPECTRAL_ARROW, "§e-" + jump() + " Seiten"));
                box.setItem(52, named(Items.SPECTRAL_ARROW, "§e+" + jump() + " Seiten"));
            }
            box.setItem(49, named(Items.GOLD_INGOT, "§6Dein Geld: §a$" + DonutMod.fmt(m.eco.get(p.getUUID())),
                    "§7Seite " + (page + 1) + "/" + pages(), "§7Einträge: §f" + view.size()));
            for (Map.Entry<Integer, Supplier<ItemStack>> e : icons.entrySet()) box.setItem(e.getKey(), e.getValue().get());
        }

        void click(int slot, ServerPlayer pl) {
            boolean reload = false;
            if (slot < 45) {
                int idx = page * 45 + slot;
                if (idx < view.size()) {
                    T item = view.get(idx);
                    onClick.accept(item, pl);
                    if (alive != null) { if (!alive.test(item)) view.remove(item); } else reload = true;
                }
            } else if (acts.containsKey(slot)) { acts.get(slot).run(); reload = true; }
            else if (slot == 45 && page > 0) page--;
            else if (slot == 53 && page < pages() - 1) page++;
            else if (slot == 46 && pages() > 10) page = Math.max(0, page - jump());
            else if (slot == 52 && pages() > 10) page = Math.min(pages() - 1, page + jump());
            if (reload) load();
            render();
        }

        void open() {
            load();
            render();
            p.openMenu(new SimpleMenuProvider((id, inv, pl) ->
                    new Menu(id, inv, box, true, Set.<Integer>of(), (s, sp) -> click(s, sp), null, null),
                    Component.literal(title)));
        }
    }

    // ---------- Auktionshaus ----------
    static void openAh(DonutMod m, ServerPlayer p) { openAh(m, p, ""); }

    static void openAh(DonutMod m, ServerPlayer p, String search) {
        int[] st = {0, 0}; // [0] Sortierung, [1] 1 = nur meine Angebote
        String[] q = {search.trim().toLowerCase(Locale.ROOT)};
        UUID[] who = {m.uuidOfName(search)};
        String[] sortNames = {"Neueste zuerst", "Günstigste zuerst", "Teuerste zuerst", "Nach Item-Name"};
        ListGui<AuctionHouse.Listing> g = new ListGui<>(m, p, who[0] != null ? "Auktionshaus: " + m.playerName(who[0]) : q[0].isEmpty() ? "Auktionshaus" : "Auktionshaus: " + q[0],
                () -> {
                    List<AuctionHouse.Listing> l = m.market.ahSorted(st[0]);
                    if (st[1] == 1) l.removeIf(x -> !p.getUUID().equals(x.seller));
                    if (who[0] != null) l.removeIf(x -> !who[0].equals(x.seller));
                    else if (!q[0].isEmpty())
                        l.removeIf(x -> !matches(q[0], x.stack.id(), x.stack.enchants(), DonutMod.ITEM_BY_ID.get(x.stack.id())));
                    return l;
                },
                l -> {
                    ItemStack base = m.ahItems.get(l.id);
                    if (base == null) base = m.buildStack(l.stack.id(), l.stack.enchants(), l.stack.count());
                    if (base.isEmpty()) return named(Items.BARRIER, "§cFehler");
                    boolean own = p.getUUID().equals(l.seller);
                    return withLore(base, "§7Preis: §a$" + DonutMod.fmt(l.price),
                            "§7Verkäufer: §f" + (l.seller == null ? "Server" : own ? "Du" : m.playerName(l.seller)), "",
                            own ? "§eKlick: Angebot zurückholen" : "§aKlick: Sofort kaufen");
                },
                (l, pl) -> {
                    if (pl.getUUID().equals(l.seller)) {
                        if (m.ah.cancel(l)) {
                            m.market.ahRemoved(l);
                            ItemStack it = m.ahItems.remove(l.id);
                            if (it == null) it = m.buildStack(l.stack.id(), l.stack.enchants(), l.stack.count());
                            if (!it.isEmpty()) pl.getInventory().placeItemBackInInventory(it);
                            say(pl, "§eAngebot zurückgeholt.");
                        }
                        return;
                    }
                    if (!m.ah.buy(pl.getUUID(), l)) { say(pl, "§cNicht genug Geld oder schon verkauft."); return; }
                    m.market.ahRemoved(l);
                    m.prices.bump(l.stack.id(), 0.0008 * l.stack.count());   // wird viel gekauft -> teurer
                    m.market.onBought(l.stack.id(), 1.0);
                    ItemStack it = m.ahItems.remove(l.id);
                    if (it == null) it = m.buildStack(l.stack.id(), l.stack.enchants(), l.stack.count());
                    if (!it.isEmpty()) pl.getInventory().placeItemBackInInventory(it);
                    DonutMod.ping(pl, SoundEvents.VILLAGER_YES, 1.0f);
                    say(pl, "§aGekauft für $" + DonutMod.fmt(l.price));
                });
        g.alive = l -> !l.removed;
        g.extra(47, () -> named(Items.HOPPER, "§eSortierung", "§7Aktuell: §f" + sortNames[st[0]], "§7Klick: nächste Sortierung"),
                () -> st[0] = (st[0] + 1) % 4);
        g.extra(48, () -> who[0] != null ? named(Items.PLAYER_HEAD, "§eSpieler: §f" + m.playerName(who[0]), "§7Klick: Filter löschen")
                        : q[0].isEmpty() ? pane() : named(Items.NAME_TAG, "§eSuche: §f" + q[0], "§7Klick: Suche löschen"),
                () -> { q[0] = ""; who[0] = null; g.page = 0; });
        g.extra(51, () -> st[1] == 0
                        ? named(Items.CHEST, "§bMeine Angebote", "§7Klick: nur deine Angebote zeigen", "§7Suche: §f/ah <Name>")
                        : named(Items.BARRIER, "§cZurück", "§7Klick: alle Angebote zeigen"),
                () -> { st[1] = 1 - st[1]; g.page = 0; });
        g.open();
    }

    // ---------- Orders ----------
    static void openOrders(DonutMod m, ServerPlayer p) { openOrders(m, p, ""); }

    static void openOrders(DonutMod m, ServerPlayer p, String search) {
        boolean[] mine = {false};
        String[] q = {search.trim().toLowerCase(Locale.ROOT)};
        UUID[] who = {m.uuidOfName(search)};
        ListGui<Orders.Order> g = new ListGui<>(m, p, who[0] != null ? "Orders: " + m.playerName(who[0]) : q[0].isEmpty() ? "Orders" : "Orders: " + q[0],
                () -> {
                    List<Orders.Order> l = m.market.ordersSorted();
                    if (mine[0]) l.removeIf(o -> !p.getUUID().equals(o.owner));
                    if (who[0] != null) l.removeIf(o -> !who[0].equals(o.owner));
                    else if (!q[0].isEmpty())
                        l.removeIf(o -> !matches(q[0], o.itemId, o.enchants, DonutMod.ITEM_BY_ID.get(o.itemId)));
                    return l;
                },
                o -> {
                    ItemStack proto = m.buildStack(o.itemId, o.enchants, 1);
                    if (proto.isEmpty()) return named(Items.BARRIER, "§cUnbekanntes Item");
                    boolean own = p.getUUID().equals(o.owner);
                    int max = Math.max(1, proto.getMaxStackSize());
                    ItemStack base = m.buildStack(o.itemId, o.enchants, Math.max(1, Math.min(o.remaining, max)));
                    return withLore(base,
                            "§7Zahlt: §a$" + DonutMod.fmt(o.pricePerItem) + " §7pro Stück",
                            "§7Gesucht: §f" + DonutMod.fmt(o.remaining) + " Stück",
                            "§7Gesamt: §a$" + DonutMod.fmt(o.pricePerItem * o.remaining),
                            "§7Besteller: §f" + (o.owner == null ? "Server" : own ? "Du" : m.playerName(o.owner)), "",
                            own ? "§eKlick: Order abbrechen (Geld zurück)" : "§aKlick: passende Items aus deinem Inventar liefern");
                },
                (o, pl) -> deliver(m, pl, o));
        g.alive = o -> !o.removed;
        g.extra(47, () -> mine[0]
                        ? named(Items.BARRIER, "§cAlle Orders", "§7Klick: alle Orders zeigen")
                        : named(Items.CHEST, "§bMeine Orders", "§7Klick: nur deine Orders zeigen", "",
                                "§7Neue Order: §f/order create <Menge> <Preis>", "§7(Item in der Hand, Preis pro Stück)",
                                "§7Suche: §f/order <Name>"),
                () -> { mine[0] = !mine[0]; g.page = 0; });
        g.extra(48, () -> who[0] != null ? named(Items.PLAYER_HEAD, "§eSpieler: §f" + m.playerName(who[0]), "§7Klick: Filter löschen")
                        : q[0].isEmpty() ? pane() : named(Items.NAME_TAG, "§eSuche: §f" + q[0], "§7Klick: Suche löschen"),
                () -> { q[0] = ""; who[0] = null; g.page = 0; });
        g.extra(50, () -> mine[0] ? named(Items.ANVIL, "§aNeue Order erstellen", "§7Klick: Item im Chat suchen, dann Menge,", "§7Preis und Verzauberungen einstellen") : pane(),
                () -> { if (mine[0]) OrderBuilder.start(m, p); });
        g.extra(51, () -> named(Items.ENDER_CHEST, "§dAbholen",
                        "§7Gelieferte Items: §f" + m.pendingCount(p.getUUID()), "§7Klick: Lager öffnen"),
                () -> openStorage(m, p));
        g.open();
    }

    static boolean satisfies(Stack have, Map<String, Integer> need) {
        for (Map.Entry<String, Integer> e : need.entrySet())
            if (have.enchants().getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        return true;
    }

    static void deliver(DonutMod m, ServerPlayer pl, Orders.Order o) {
        if (pl.getUUID().equals(o.owner)) {
            long back = o.pricePerItem * o.remaining;
            if (m.orders.cancel(o)) { m.market.ordersRemoved(o); say(pl, "§eOrder abgebrochen, $" + DonutMod.fmt(back) + " zurück."); }
            return;
        }
        Inventory inv = pl.getInventory();
        int need = o.remaining, got = 0;
        List<ItemStack> taken = new ArrayList<>();
        for (int i = 0; i < 36 && got < need; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            Stack ts = DonutMod.toStack(s);
            if (ts.id().equals(o.itemId) && satisfies(ts, o.enchants)) {
                int take = Math.min(s.getCount(), need - got);
                taken.add(s.copyWithCount(take));
                s.shrink(take);
                got += take;
                if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            }
        }
        if (got == 0) { say(pl, "§cDu hast dazu passende Items nicht im Inventar."); return; }
        UUID owner = o.owner;
        long pay = m.orders.fill(o, new Stack(o.itemId, got, o.enchants), pl.getUUID());
        if (owner != null) for (ItemStack c : taken) m.addPending(owner, c);
        m.prices.bump(o.itemId, -0.0004 * got);   // wird viel verkauft -> guenstiger
        if (o.removed) m.market.ordersRemoved(o);
        DonutMod.ping(pl, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f);
        say(pl, "§aGeliefert: " + got + "x für $" + DonutMod.fmt(pay));
    }

    // ---------- Lager (gelieferte Order-Items) ----------
    static final class Group {
        final String id; final Item item; int count, stacks;
        Group(String id, Item item) { this.id = id; this.item = item; }
    }

    static List<Group> groups(DonutMod m, UUID u) {
        Map<String, Group> map = new LinkedHashMap<>();
        for (ItemStack s : m.pending.getOrDefault(u, List.of())) {
            String id = DonutMod.idOfStatic(s);
            Group g = map.computeIfAbsent(id, k -> new Group(k, s.getItem()));
            g.count += s.getCount();
            g.stacks++;
        }
        return new ArrayList<>(map.values());
    }

    static List<ItemStack> stacksOf(DonutMod m, UUID u, String id) {
        List<ItemStack> r = new ArrayList<>();
        for (ItemStack s : m.pending.getOrDefault(u, List.of())) if (DonutMod.idOfStatic(s).equals(id)) r.add(s);
        return r;
    }

    static void openStorage(DonutMod m, ServerPlayer p) {
        ListGui<Group> g = new ListGui<>(m, p, "Lager - gelieferte Items",
                () -> groups(m, p.getUUID()),
                gr -> withLore(new ItemStack(gr.item, Math.max(1, Math.min(gr.count, 64))),
                        "§7Gelagert: §f" + gr.count + " Items", "§7Stacks: §f" + gr.stacks, "", "§aKlick: Items ansehen"),
                (gr, pl) -> openGroup(m, pl, gr.id));
        g.extra(52, () -> named(Items.ARROW, "§eZurück zu Orders"), () -> openOrders(m, p));
        g.open();
    }

    static void openGroup(DonutMod m, ServerPlayer p, String itemId) {
        UUID u = p.getUUID();
        boolean shulkerGroup = itemId.endsWith("shulker_box");
        ListGui<ItemStack> g = new ListGui<>(m, p, "Lager: " + itemId.substring(itemId.indexOf(':') + 1).replace('_', ' '),
                () -> stacksOf(m, u, itemId),
                s -> withLore(s, "§7Menge: §f" + s.getCount(), "", "§aKlick / Shift-Klick: ins Inventar"),
                (s, pl) -> takeOne(m, pl, s));
        g.extra(47, () -> named(Items.DROPPER, "§6Seite droppen", "§7Wirft diese Seite auf den Boden"),
                () -> dropOrFill(m, p, g.pageItems(), true));
        g.extra(48, () -> named(Items.TNT, "§cAlles droppen", "§7Wirft alle Stacks dieses Items auf den Boden"),
                () -> dropOrFill(m, p, new ArrayList<>(g.view), true));
        g.extra(50, () -> named(Items.HOPPER, "§aInventar füllen", "§7Füllt dein Inventar, solange Platz ist"),
                () -> dropOrFill(m, p, new ArrayList<>(g.view), false));
        if (!shulkerGroup)
            g.extra(51, () -> m.shulkerOn.contains(u)
                            ? named(Items.LIME_DYE, "§dIn Shulker: §aAN", "§7Droppen/Füllen packt die Items in",
                                    "§7leere Shulker aus deinem Inventar", "§7Klick: ausschalten")
                            : named(Items.GRAY_DYE, "§dIn Shulker: §cAUS", "§7Klick: einschalten"),
                    () -> { if (!m.shulkerOn.remove(u)) m.shulkerOn.add(u); });
        g.extra(52, () -> named(Items.ARROW, "§eZurück"), () -> openStorage(m, p));
        g.open();
    }

    static void takeOne(DonutMod m, ServerPlayer pl, ItemStack s) {
        List<ItemStack> list = m.pending.get(pl.getUUID());
        if (list == null) return;
        ItemStack c = s.copy();
        pl.getInventory().add(c);
        if (c.isEmpty()) list.remove(s); else if (c.getCount() == s.getCount()) say(pl, "§cInventar voll."); else s.setCount(c.getCount());
        if (list.isEmpty()) m.pending.remove(pl.getUUID());
    }

    static boolean isEmptyShulker(ItemStack s) {
        if (!DonutMod.isShulker(s)) return false;
        ItemContainerContents cc = s.get(DataComponents.CONTAINER);
        return cc == null || cc.equals(ItemContainerContents.EMPTY);
    }

    /** Packt je 27 Stacks in einen leeren Shulker (jede Farbe) aus dem Inventar, sonst aus dem Lager (gelieferte Items). */
    static void packShulkers(DonutMod m, ServerPlayer p, List<ItemStack> src, List<ItemStack> out, List<ItemStack> used) {
        Inventory inv = p.getInventory();
        List<ItemStack> lager = m.pending.getOrDefault(p.getUUID(), new ArrayList<>());
        int idx = 0;
        while (idx < src.size()) {
            ItemStack box = null;
            for (int i = 0; i < 36; i++)
                if (isEmptyShulker(inv.getItem(i))) { box = inv.getItem(i).copy(); inv.setItem(i, ItemStack.EMPTY); break; }
            if (box == null)
                for (int i = 0; i < lager.size(); i++)
                    if (isEmptyShulker(lager.get(i))) { box = lager.get(i).copyWithCount(1); lager.remove(i); break; }
            if (box == null) break;
            List<ItemStack> chunk = new ArrayList<>();
            for (int k = 0; k < 27 && idx < src.size(); k++, idx++) {
                chunk.add(src.get(idx).copy());
                used.add(src.get(idx));
            }
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(chunk));
            out.add(box);
        }
    }

    static void dropOrFill(DonutMod m, ServerPlayer p, List<ItemStack> sel, boolean drop) {
        if (sel.isEmpty()) { say(p, "§7Nichts da."); return; }
        List<ItemStack> list = m.pending.get(p.getUUID());
        if (list == null) return;
        boolean sh = m.shulkerOn.contains(p.getUUID()) && !DonutMod.isShulker(sel.get(0));
        int moved = 0;
        if (sh) {
            List<ItemStack> out = new ArrayList<>(), used = new ArrayList<>();
            packShulkers(m, p, sel, out, used);
            if (used.isEmpty()) { say(p, "§cKeine leeren Shulker im Inventar oder Lager."); return; }
            for (ItemStack u : used) moved += u.getCount();
            Set<ItemStack> ident = Collections.newSetFromMap(new IdentityHashMap<>());
            ident.addAll(used);
            list.removeIf(ident::contains);
            for (ItemStack o : out) {
                if (drop) p.drop(o, false);
                else { p.getInventory().add(o); if (!o.isEmpty()) p.drop(o, false); }
            }
        } else if (drop) {
            Set<ItemStack> ident = Collections.newSetFromMap(new IdentityHashMap<>());
            ident.addAll(sel);
            for (ItemStack s : sel) { moved += s.getCount(); p.drop(s.copy(), false); }
            list.removeIf(ident::contains);
        } else {
            for (ItemStack s : sel) {
                ItemStack c = s.copy();
                p.getInventory().add(c);
                moved += s.getCount() - c.getCount();
                if (c.isEmpty()) list.remove(s); else { s.setCount(c.getCount()); break; }
            }
        }
        if (list.isEmpty()) m.pending.remove(p.getUUID());
        say(p, "§a" + moved + " Items " + (drop ? "gedroppt" : "ins Inventar gelegt") + (sh ? " (in Shulkern)." : "."));
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
            if (!s.isEmpty()) DonutMod.addStacks(s, list);
        }
        return list;
    }

    static ItemStack sellButton(DonutMod m, SimpleContainer box) {
        List<Stack> list = collect(box);
        long v = list.isEmpty() ? 0 : m.sell.preview(list);
        return named(Items.LIME_STAINED_GLASS_PANE, "§aVerkaufen: $" + DonutMod.fmt(v),
                "§7Klick hier oder schließe das Menü.", "§7Shulker: Inhalt zählt mit.");
    }

    static void sellAll(DonutMod m, SimpleContainer box, ServerPlayer p) {
        List<Stack> list = collect(box);
        for (int i = 0; i < 53; i++) box.setItem(i, ItemStack.EMPTY);
        if (list.isEmpty()) return;
        long pay = m.sell.sell(p.getUUID(), list).payout();
        DonutMod.ping(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f);
        say(p, "§aVerkauft für $" + DonutMod.fmt(pay) + " §7| Kontostand: $" + DonutMod.fmt(m.eco.get(p.getUUID())));
    }
}
