package de.donutmod.fabric;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import de.donutmod.core.*;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.nio.file.*;
import java.util.*;

/** Fabric-Anbindung (Mojang-Mappings, MC 1.21.11) an den versionsunabhaengigen Core. */
public class DonutMod implements ModInitializer {
    static final Random RND = new Random();
    static final List<String> META_POOL = List.of("minecraft:kelp", "minecraft:diamond", "minecraft:iron_ingot",
            "minecraft:gold_ingot", "minecraft:sea_lantern", "minecraft:cactus", "minecraft:bone_meal");

    final PriceBook prices = new PriceBook();
    final MetaManager meta = new MetaManager();
    final Economy eco = new Economy();
    final Orders orders = new Orders(prices, meta, eco);
    final AuctionHouse ah = new AuctionHouse(prices, meta, eco);
    final SellService sell = new SellService(prices, orders, eco);
    /** Echte ItemStacks zu den AH-Angeboten (Core kennt nur Stack-Beschreibungen). */
    final Map<Integer, ItemStack> ahItems = new HashMap<>();
    /** Pro Spieler eigenes Sidebar-Scoreboard (rechts, mittig) mit dem eigenen Kontostand. */
    final Map<UUID, Objective> boards = new HashMap<>();
    static final Gson GSON = new Gson();
    static Path saveFile() { return FabricLoader.getInstance().getConfigDir().resolve("donutmod-balances.json"); }

    @Override
    public void onInitialize() {
        prices.setBase("minecraft:kelp", 50);
        prices.setBase("minecraft:diamond", 500);
        prices.setBase("minecraft:iron_ingot", 80);
        prices.setBase("minecraft:gold_ingot", 120);
        prices.setBase("minecraft:sea_lantern", 300);
        prices.setBase("minecraft:netherite_ingot", 5_000);
        prices.setBase("minecraft:netherite_chestplate", 60_000);
        prices.setEnchantValue("minecraft:protection", 20_000);
        prices.setEnchantValue("minecraft:sharpness", 15_000);
        prices.setEnchantValue("minecraft:mending", 100_000);
        meta.roll(META_POOL, 2, RND);
        seedOrders();
        seedAh();

        CommandRegistrationCallback.EVENT.register((d, ctx, env) -> registerCommands(d));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> loadBalances());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveBalances());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> createBoard(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> boards.remove(handler.getPlayer().getUUID()));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int t = server.getTickCount();
            if (t % 20 == 0) updateBoards(server);          // jede Sekunde Anzeige aktualisieren
            if (t % 6000 == 0) saveBalances();              // alle 5 Min speichern
            if (t % 1200 == 0) botCycle(server);          // jede Minute
            if (t % 36000 == 0) {                           // alle 30 Min neue Meta
                meta.roll(META_POOL, 2, RND);
                seedOrders();
            }
        });
    }

    // ---- Speichern ----
    void saveBalances() {
        try {
            Map<String, Long> out = new HashMap<>();
            eco.snapshot().forEach((u, v) -> out.put(u.toString(), v));
            Files.writeString(saveFile(), GSON.toJson(out));
        } catch (Exception e) { System.err.println("[DonutMod] Speichern fehlgeschlagen: " + e); }
    }

    void loadBalances() {
        try {
            if (!Files.exists(saveFile())) return;
            Map<String, Long> in = GSON.fromJson(Files.readString(saveFile()), new TypeToken<Map<String, Long>>() {}.getType());
            Map<UUID, Long> data = new HashMap<>();
            if (in != null) in.forEach((k, v) -> data.put(UUID.fromString(k), v));
            eco.load(data);
        } catch (Exception e) { System.err.println("[DonutMod] Laden fehlgeschlagen: " + e); }
    }

    // ---- Scoreboard (Sidebar rechts, mittig) ----
    void createBoard(ServerPlayer p) {
        Objective obj = new Scoreboard().addObjective("donutmod_money", ObjectiveCriteria.DUMMY,
                Component.literal("§6§lDonutMod"), ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
        boards.put(p.getUUID(), obj);
        p.connection.send(new ClientboundSetObjectivePacket(obj, 0));                 // 0 = erstellen
        p.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, obj));
        sendMoney(p, obj);
    }

    void sendMoney(ServerPlayer p, Objective obj) {
        p.connection.send(new ClientboundSetScorePacket("money", obj.getName(), 0,
                Optional.of(Component.literal("§eGeld: §a$" + fmt(eco.get(p.getUUID())))), Optional.of(BlankFormat.INSTANCE)));
    }

    void updateBoards(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Objective obj = boards.get(p.getUUID());
            if (obj == null) createBoard(p); else sendMoney(p, obj);
        }
    }

    // ---- Bot-Logik ----
    void seedOrders() {
        for (String id : META_POOL) orders.botOrder(id, Map.of(), 64 * (1 + RND.nextInt(4)), RND);
    }

    void seedAh() {
        ItemStack[] pool = { new ItemStack(Items.KELP, 64), new ItemStack(Items.DIAMOND, 32),
                new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.SEA_LANTERN, 64) };
        while (ah.all().size() < 12) addBotListing(pool[RND.nextInt(pool.length)].copy());
    }

    void addBotListing(ItemStack st) {
        AuctionHouse.Listing l = ah.botList(toStack(st), RND);
        ahItems.put(l.id, st);
    }

    void botCycle(MinecraftServer server) {
        for (AuctionHouse.Listing l : ah.tickBotBuyers(RND)) {
            ItemStack st = ahItems.remove(l.id);
            if (l.seller != null) {
                ServerPlayer p = server.getPlayerList().getPlayer(l.seller);
                if (p != null) p.sendSystemMessage(Component.literal("§a[AH] Dein Angebot #" + l.id
                        + (st != null ? " (" + st.getHoverName().getString() + ")" : "") + " wurde fuer $" + fmt(l.price) + " verkauft."));
            }
        }
        if (ah.all().size() < 12) seedAh();
        if (orders.all().size() < 4) seedOrders();
    }

    // ---- Umrechnung MC <-> Core ----
    static Stack toStack(ItemStack st) {
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
        Map<String, Integer> ench = new HashMap<>();
        ItemEnchantments ie = st.get(DataComponents.ENCHANTMENTS);
        if (ie != null)
            for (Object2IntMap.Entry<net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>> e : ie.entrySet())
                ench.put(e.getKey().getRegisteredName(), e.getIntValue());
        return new Stack(id, st.getCount(), ench);
    }

    static String fmt(long n) { return String.format(Locale.GERMANY, "%,d", n); }

    // ---- Commands ----
    void registerCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("sell")
                .executes(c -> doSell(c, false))
                .then(Commands.literal("all").executes(c -> doSell(c, true)))
                .then(Commands.literal("preview").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    ItemStack h = p.getMainHandItem();
                    long v = h.isEmpty() ? 0 : sell.preview(List.of(toStack(h)));
                    return msg(c, "§eVorschau: §a$" + fmt(v));
                })));

        d.register(Commands.literal("bal").executes(c -> msg(c,
                "§eKontostand: §a$" + fmt(eco.get(c.getSource().getPlayerOrException().getUUID())))));

        d.register(Commands.literal("meta").executes(c -> {
            StringBuilder sb = new StringBuilder("§6Aktuelle Metas:");
            meta.current().forEach((id, b) -> sb.append("\n§e").append(id).append(" §7(+").append(Math.round(b * 100)).append("%)"));
            return msg(c, sb.toString());
        }));

        d.register(Commands.literal("orders").executes(c -> {
            StringBuilder sb = new StringBuilder("§6Offene Orders:");
            for (Orders.Order o : orders.all())
                sb.append("\n§e#").append(o.id).append(" §f").append(o.itemId).append(" §7x").append(o.remaining)
                  .append(" §a$").append(fmt(o.pricePerItem)).append("§7/Stk");
            return msg(c, sb.toString());
        }));

        d.register(Commands.literal("ah")
                .executes(c -> {
                    StringBuilder sb = new StringBuilder("§6Auktionshaus:");
                    for (AuctionHouse.Listing l : ah.all()) {
                        ItemStack st = ahItems.get(l.id);
                        sb.append("\n§e#").append(l.id).append(" §f")
                          .append(st != null ? st.getCount() + "x " + st.getHoverName().getString() : l.stack.id())
                          .append(" §a$").append(fmt(l.price));
                    }
                    return msg(c, sb.toString());
                })
                .then(Commands.literal("sell").then(Commands.argument("preis", LongArgumentType.longArg(1))
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            ItemStack h = p.getMainHandItem();
                            if (h.isEmpty()) return msg(c, "§cDu hast nichts in der Hand.");
                            ItemStack copy = h.copy();
                            AuctionHouse.Listing l = ah.list(p.getUUID(), toStack(copy), LongArgumentType.getLong(c, "preis"));
                            ahItems.put(l.id, copy);
                            p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                            return msg(c, "§aAngebot #" + l.id + " eingestellt fuer $" + fmt(l.price));
                        })))
                .then(Commands.literal("buy").then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            int id = IntegerArgumentType.getInteger(c, "id");
                            AuctionHouse.Listing l = ah.all().stream().filter(x -> x.id == id).findFirst().orElse(null);
                            if (l == null) return msg(c, "§cAngebot nicht gefunden.");
                            if (!ah.buy(p.getUUID(), l)) return msg(c, "§cNicht genug Geld.");
                            ItemStack st = ahItems.remove(id);
                            if (st != null) p.getInventory().placeItemBackInInventory(st);
                            return msg(c, "§aGekauft fuer $" + fmt(l.price));
                        }))));
    }

    int doSell(CommandContext<CommandSourceStack> c, boolean all) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        List<Stack> stacks = new ArrayList<>();
        if (all) {
            var inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (!st.isEmpty()) { stacks.add(toStack(st)); inv.setItem(i, ItemStack.EMPTY); }
            }
        } else {
            ItemStack h = p.getMainHandItem();
            if (!h.isEmpty()) { stacks.add(toStack(h)); p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); }
        }
        if (stacks.isEmpty()) return msg(c, "§cNichts zu verkaufen.");
        long pay = sell.sell(p.getUUID(), stacks).payout();
        return msg(c, "§aVerkauft fuer $" + fmt(pay) + " §7| Kontostand: $" + fmt(eco.get(p.getUUID())));
    }

    static int msg(CommandContext<CommandSourceStack> c, String s) {
        c.getSource().sendSuccess(() -> Component.literal(s), false);
        return 1;
    }
}
