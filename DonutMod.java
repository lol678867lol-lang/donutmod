package de.donutmod;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import de.donutmod.core.AuctionHouse;
import de.donutmod.core.Orders;
import de.donutmod.core.Stack;
import de.donutmod.gui.ListHandler;
import de.donutmod.gui.SellHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;

public class DonutMod implements ModInitializer {
    private int ticks = 0;

    @Override
    public void onInitialize() {
        Settings.load();
        ServerLifecycleEvents.SERVER_STARTED.register(Market::init);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Market.shutdown());
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (!Market.ready) return;
            ticks++;
            if (ticks % 20 == 0) NameTags.tick(s);               // Money Name Tags
            if (ticks % 40 == 0) Easter.tick(s);                 // Easter Egg
            if (ticks % 1200 == 0) Market.minuteTick();          // jede Minute
            if (ticks % 12000 == 0) Market.rollMetas();          // alle 10 Minuten neue Metas
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
            dispatcher.register(CommandManager.literal("sell").executes(c -> {
                SellHandler.open(c.getSource().getPlayerOrThrow());
                return 1;
            }));

            dispatcher.register(CommandManager.literal("market").executes(c -> {
                c.getSource().sendFeedback(() -> Text.literal("Erkannte Mods: " + Market.modCount
                    + " | Items im Handel: " + Market.ALL_ITEMS_SIZE()
                    + " | Metas: " + Market.meta.current().keySet()), false);
                return 1;
            }));

            dispatcher.register(CommandManager.literal("settings")
                .executes(c -> { openSettings(c.getSource().getPlayerOrThrow()); return 1; }));

            // /mod on|off und /opsettings: nur der Welt-Besitzer (Singleplayer)
            dispatcher.register(CommandManager.literal("mod")
                .requires(src -> src.getServer().isSingleplayer())
                .executes(c -> {
                    c.getSource().sendFeedback(() -> Text.literal("Mod-Items: " + (Market.modItems ? "AN" : "AUS")), false);
                    return 1;
                })
                .then(CommandManager.literal("on").executes(c -> {
                    Market.setModItems(true);
                    c.getSource().sendFeedback(() -> Text.literal("Mod-Items: AN (verkaufbar, in /ah und Orders)"), true);
                    return 1;
                }))
                .then(CommandManager.literal("off").executes(c -> {
                    Market.setModItems(false);
                    c.getSource().sendFeedback(() -> Text.literal("Mod-Items: AUS (nicht verkaufbar, nicht in /ah und Orders)"), true);
                    return 1;
                })));

            dispatcher.register(CommandManager.literal("opsettings")
                .requires(src -> src.getServer().isSingleplayer())
                .executes(c -> { openOpSettings(c.getSource().getPlayerOrThrow()); return 1; }));

            dispatcher.register(CommandManager.literal("bal").executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                c.getSource().sendFeedback(() -> Text.literal(Market.fmt(Market.eco.get(p.getUuid()))), false);
                return 1;
            }));

            // /ah  -> GUI,  /ah <preis> -> Item in der Hand einstellen
            dispatcher.register(CommandManager.literal("ah")
                .executes(c -> { openAh(c.getSource().getPlayerOrThrow()); return 1; })
                .then(CommandManager.argument("preis", LongArgumentType.longArg(1)).executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                    ItemStack hand = p.getMainHandStack();
                    if (hand.isEmpty()) {
                        c.getSource().sendFeedback(() -> Text.literal("Du hast nichts in der Hand."), false);
                        return 0;
                    }
                    if (!Market.allowed(Market.toCore(hand).id())) {
                        c.getSource().sendFeedback(() -> Text.literal("Items anderer Mods sind ausgeschaltet (/mod on)."), false);
                        return 0;
                    }
                    long price = LongArgumentType.getLong(c, "preis");
                    Market.ah.list(p.getUuid(), Market.toCore(hand), price);
                    p.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
                    c.getSource().sendFeedback(() -> Text.literal("Eingestellt für " + Market.fmt(price)), false);
                    return 1;
                })));

            // /order  -> GUI,  /order <item> <menge> <preis pro stück> -> Order erstellen
            dispatcher.register(CommandManager.literal("order")
                .executes(c -> { openOrders(c.getSource().getPlayerOrThrow()); return 1; })
                .then(CommandManager.argument("item", ItemStackArgumentType.itemStack(registryAccess))
                .then(CommandManager.argument("menge", IntegerArgumentType.integer(1, 2304))
                .then(CommandManager.argument("preis", LongArgumentType.longArg(1)).executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                    String id = Registries.ITEM.getId(ItemStackArgumentType.getItemStackArgument(c, "item").getItem()).toString();
                    if (!Market.allowed(id)) {
                        c.getSource().sendFeedback(() -> Text.literal("Items anderer Mods sind ausgeschaltet (/mod on)."), false);
                        return 0;
                    }
                    int n = IntegerArgumentType.getInteger(c, "menge");
                    long ppi = LongArgumentType.getLong(c, "preis");
                    Orders.Order o = Market.orders.playerOrder(p.getUuid(), id, java.util.Map.of(), n, ppi);
                    c.getSource().sendFeedback(() -> Text.literal(o == null
                        ? "Nicht genug Geld (" + Market.fmt(ppi * n) + " nötig)."
                        : "Order erstellt, Bots liefern in Kürze."), false);
                    return o == null ? 0 : 1;
                })))));
        });
    }

    private static ItemStack icon(Stack s, String... lore) {
        ItemStack st = Market.toMc(s);
        List<Text> lines = new ArrayList<>();
        for (String l : lore) lines.add(Text.literal(l));
        st.set(DataComponentTypes.LORE, new LoreComponent(lines));
        return st;
    }

    static void openAh(ServerPlayerEntity p) {
        List<AuctionHouse.Listing> ls = new ArrayList<>(Market.ah.all().stream().filter(l -> Market.allowed(l.stack.id())).toList());
        ItemStack[] icons = new ItemStack[Math.min(54, ls.size())];
        for (int i = 0; i < icons.length; i++) {
            var l = ls.get(i);
            String meta = Market.meta.isMeta(l.stack.id())
                ? "META +" + Math.round(Market.meta.bonus(l.stack.id()) * 100) + " %" : "";
            icons[i] = icon(l.stack, "Preis: " + Market.fmt(l.price), meta, "Klicken zum Kaufen");
        }
        ListHandler.open(p, "Auktionshaus", icons, slot -> {
            if (slot >= ls.size()) return;
            var l = ls.get(slot);
            if (Market.ah.buy(p.getUuid(), l)) {
                Market.give(p, l.stack);
                p.sendMessage(Text.literal("Gekauft für " + Market.fmt(l.price)), false);
            } else p.sendMessage(Text.literal("Nicht genug Geld oder schon verkauft."), false);
            p.closeHandledScreen();
        });
    }

    private static ItemStack toggle(String name, boolean on, String... info) {
        ItemStack t = new ItemStack(on ? net.minecraft.item.Items.LIME_CONCRETE : net.minecraft.item.Items.RED_CONCRETE);
        t.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name + ": " + (on ? "AN" : "AUS")));
        List<Text> lines = new ArrayList<>();
        for (String i : info) lines.add(Text.literal(i));
        lines.add(Text.literal("Klicken zum Umschalten"));
        t.set(DataComponentTypes.LORE, new LoreComponent(lines));
        return t;
    }

    static void openSettings(ServerPlayerEntity p) {
        ItemStack[] icons = new ItemStack[54];
        java.util.Arrays.fill(icons, ItemStack.EMPTY);
        icons[19] = toggle("Geld-Anzeige", Settings.moneyHud, "DonutSMP / Geld / Economy auf dem Bildschirm");
        icons[21] = toggle("Money Name Tags", Settings.moneyTags, "Geld hinter dem Namen über dem Kopf von Spielern");
        icons[23] = toggle("Kurze Zahlen (k/m/b/t)", Settings.shortNumbers, "3587 wird zu 3.5k, überall angezeigt");
        icons[25] = toggle("Anzeige rechts in der Mitte", Settings.hudRight, "Aus = oben links");
        ListHandler.open(p, "Settings", icons, slot -> {
            switch (slot) {
                case 19 -> Settings.moneyHud = !Settings.moneyHud;
                case 21 -> Settings.moneyTags = !Settings.moneyTags;
                case 23 -> Settings.shortNumbers = !Settings.shortNumbers;
                case 25 -> Settings.hudRight = !Settings.hudRight;
                default -> { return; }
            }
            Settings.save();
            openSettings(p);
        });
    }

    static void openOpSettings(ServerPlayerEntity p) {
        ItemStack[] icons = new ItemStack[54];
        java.util.Arrays.fill(icons, ItemStack.EMPTY);
        ItemStack t = new ItemStack(Market.modItems ? net.minecraft.item.Items.LIME_CONCRETE : net.minecraft.item.Items.RED_CONCRETE);
        t.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Mod-Items: " + (Market.modItems ? "AN" : "AUS")));
        t.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            Text.literal("Aus = Items anderer Mods werden nicht verkauft"),
            Text.literal("und sind nicht in /ah und Orders."),
            Text.literal("Klicken zum Umschalten"))));
        icons[22] = t;
        ListHandler.open(p, "OP-Settings", icons, slot -> {
            if (slot != 22) return;
            Market.setModItems(!Market.modItems);
            openOpSettings(p);
        });
    }

    static void openOrders(ServerPlayerEntity p) {
        List<Orders.Order> os = new ArrayList<>(Market.orders.all().stream().filter(o -> Market.allowed(o.itemId)).toList());
        ItemStack[] icons = new ItemStack[Math.min(54, os.size())];
        for (int i = 0; i < icons.length; i++) {
            var o = os.get(i);
            String who = o.owner == null ? "Bot" : (o.owner.equals(p.getUuid()) ? "Du" : "Spieler");
            icons[i] = icon(new Stack(o.itemId, 1, o.enchants),
                "Preis pro Stück: " + Market.fmt(o.pricePerItem), "Benötigt: " + o.remaining, "Von: " + who,
                "Liefern per /sell (nur wenn Preis über Sell-Preis)");
        }
        ListHandler.open(p, "Orders", icons, slot -> {});
    }
}
