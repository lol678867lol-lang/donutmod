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
        ServerLifecycleEvents.SERVER_STARTED.register(Market::init);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Market.shutdown());
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (!Market.ready) return;
            ticks++;
            if (ticks % 40 == 0) Easter.tick(s);                 // Easter Egg
            if (ticks % 1200 == 0) Market.minuteTick();          // jede Minute
            if (ticks % 12000 == 0) Market.rollMetas();          // alle 10 Minuten neue Metas
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
            dispatcher.register(CommandManager.literal("sell").executes(c -> {
                SellHandler.open(c.getSource().getPlayerOrThrow());
                return 1;
            }));

            dispatcher.register(CommandManager.literal("bal").executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                c.getSource().sendFeedback(() -> Text.literal("Kontostand: " + Market.fmt(Market.eco.get(p.getUuid()))), false);
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
        List<AuctionHouse.Listing> ls = new ArrayList<>(Market.ah.all());
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

    static void openOrders(ServerPlayerEntity p) {
        List<Orders.Order> os = new ArrayList<>(Market.orders.all());
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
