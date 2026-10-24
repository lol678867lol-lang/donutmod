package de.donutmod.fabric;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.arguments.EntityArgument;
import com.mojang.brigadier.context.CommandContext;
import de.donutmod.core.*;
import de.donutmod.core.Stack;
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
import net.minecraft.world.item.Item;
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

    static final Object[][] KNOWN = {
            {"kelp",50},{"dried_kelp_block",450},{"sea_lantern",300},{"cactus",40},{"bone_meal",30},{"bone",60},
            {"string",90},{"spider_eye",70},{"rotten_flesh",20},{"gunpowder",120},{"ender_pearl",400},{"blaze_rod",250},
            {"coal",60},{"charcoal",50},{"iron_ingot",80},{"gold_ingot",120},{"copper_ingot",30},{"diamond",500},
            {"emerald",350},{"lapis_lazuli",60},{"redstone",40},{"quartz",70},{"amethyst_shard",90},
            {"netherite_scrap",1500},{"netherite_ingot",5000},{"ancient_debris",2500},{"iron_block",720},
            {"gold_block",1080},{"diamond_block",4500},{"emerald_block",3150},{"netherite_block",45000},
            {"cobblestone",5},{"stone",8},{"deepslate",8},{"obsidian",150},{"crying_obsidian",300},{"glowstone",110},
            {"oak_log",20},{"spruce_log",20},{"birch_log",20},{"sand",10},{"gravel",10},{"dirt",3},
            {"wheat",25},{"carrot",25},{"potato",25},{"melon_slice",15},{"pumpkin",40},{"sugar_cane",30},
            {"sweet_berries",20},{"golden_apple",900},{"enchanted_golden_apple",25000},{"cooked_beef",80},
            {"diamond_sword",2500},{"diamond_pickaxe",3000},{"diamond_axe",3000},{"diamond_shovel",1500},
            {"diamond_helmet",3500},{"diamond_chestplate",5600},{"diamond_leggings",5000},{"diamond_boots",2900},
            {"netherite_sword",9000},{"netherite_pickaxe",9500},{"netherite_axe",9500},{"netherite_shovel",8000},
            {"netherite_helmet",20000},{"netherite_chestplate",60000},{"netherite_leggings",50000},{"netherite_boots",15000},
            {"elytra",20000},{"totem_of_undying",15000},{"shulker_shell",1500},{"shulker_box",3500},
            {"nether_star",40000},{"beacon",60000},{"experience_bottle",300},{"enchanted_book",2000},
            {"trident",12000},{"mace",30000},{"heavy_core",20000},{"wind_charge",150},{"breeze_rod",600}
        };

    @Override
    public void onInitialize() {
        for (Object[] row : KNOWN) prices.setBase("minecraft:" + row[0], ((Number) row[1]).longValue());
        prices.setEnchantValue("minecraft:protection", 20_000);
        prices.setEnchantValue("minecraft:sharpness", 15_000);
        prices.setEnchantValue("minecraft:mending", 100_000);
        meta.roll(META_POOL, 2, RND);

        CommandRegistrationCallback.EVENT.register((d, ctx, env) -> registerCommands(d));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> { srv = server; loadBalances(); PriceFile.loadInto(prices); initItems(); seedOrders(); seedAh(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { refundOrders(); saveBalances(); });
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
    static final Map<String, Item> ITEM_BY_ID = new HashMap<>();
    final List<Item> pool = new ArrayList<>();
    static final String[] BAD = {"spawn_egg", "command_block", "structure_", "barrier", "light", "debug_stick", "jigsaw",
            "knowledge_book", "bedrock", "spawner", "test_", "trial_spawner", "vault", "farmland", "dirt_path",
            "frogspawn", "budding_amethyst", "infested", "reinforced_deepslate", "petrified", "end_portal", "player_head"};

    void initItems() {
        ITEM_BY_ID.clear();
        pool.clear();
        for (Item it : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(it).toString();
            ITEM_BY_ID.put(id, it);
            String p = id.substring(id.indexOf(':') + 1);
            if (p.equals("air")) continue;
            boolean bad = false;
            for (String b : BAD) if (p.contains(b)) { bad = true; break; }
            if (!bad) pool.add(it);
        }
    }

    /** Mindestens 500 Bot-Orders mit zufaelligen Items. */
    void seedOrders() {
        if (pool.isEmpty()) return;
        while (orders.all().size() < 500) {
            Item it = pool.get(RND.nextInt(pool.size()));
            int max = Math.max(1, it.getDefaultInstance().getMaxStackSize());
            int amt = max > 1 ? 16 + RND.nextInt(max * 3) : 1 + RND.nextInt(5);
            orders.botOrder(BuiltInRegistries.ITEM.getKey(it).toString(), Map.of(), amt, RND);
        }
    }

    /** Mindestens 500 Bot-Angebote im Auktionshaus. */
    void seedAh() {
        if (pool.isEmpty()) return;
        while (ah.all().size() < 500) {
            Item it = pool.get(RND.nextInt(pool.size()));
            int max = Math.max(1, it.getDefaultInstance().getMaxStackSize());
            addBotListing(new ItemStack(it, max > 1 ? 1 + RND.nextInt(max) : 1));
        }
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
        for (Orders.Order o : new ArrayList<>(orders.all())) {
            if (o.owner == null) continue;
            Item it = ITEM_BY_ID.get(o.itemId);
            if (it == null) continue;
            double ratio = o.pricePerItem / Math.max(1.0, (double) prices.unitPrice(o.itemId, Map.of()));
            double chance = Math.min(0.8, 0.05 * Math.pow(ratio, 3));
            if (RND.nextDouble() < chance) {
                int n = Math.max(1, (int) Math.ceil(o.remaining * (0.1 + RND.nextDouble() * 0.4)));
                int d = orders.botDeliver(o, n);
                if (d > 0) addPending(o.owner, new ItemStack(it, d));
            }
        }
        seedAh();
        seedOrders();
    }

    // ---- Gelieferte Order-Items (zum Abholen) ----
    final Map<UUID, List<ItemStack>> pending = new HashMap<>();
    MinecraftServer srv;

    void addPending(UUID owner, ItemStack st) {
        List<ItemStack> l = pending.computeIfAbsent(owner, k -> new ArrayList<>());
        int max = Math.max(1, st.getMaxStackSize());
        int left = st.getCount();
        while (left > 0) { int n = Math.min(left, max); l.add(st.copyWithCount(n)); left -= n; }
        if (srv != null) {
            ServerPlayer p = srv.getPlayerList().getPlayer(owner);
            if (p != null) p.sendSystemMessage(Component.literal("§a[Orders] Lieferung: " + st.getCount() + "x "
                    + st.getHoverName().getString() + " - hole sie mit /orders claim ab."));
        }
    }

    int pendingCount(UUID u) {
        int n = 0;
        for (ItemStack s : pending.getOrDefault(u, List.of())) n += s.getCount();
        return n;
    }

    void claim(ServerPlayer p) {
        List<ItemStack> l = pending.remove(p.getUUID());
        if (l == null || l.isEmpty()) { Gui.say(p, "§7Nichts zum Abholen."); return; }
        int n = 0;
        for (ItemStack s : l) { n += s.getCount(); p.getInventory().placeItemBackInInventory(s); }
        Gui.say(p, "§aAbgeholt: " + n + " Items.");
    }

    /** Beim Beenden: offene Spieler-Orders zurueckzahlen, damit kein Geld verloren geht. */
    void refundOrders() {
        for (Orders.Order o : new ArrayList<>(orders.all())) if (o.owner != null) orders.cancel(o);
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

    /** Betrag mit k/m/b/t-Endung, z.B. 500, 1.5k, 2m. Rueckgabe -1 bei Fehler. */
    static long parseAmount(String s) {
        try {
            s = s.trim().toLowerCase(Locale.ROOT).replace(",", ".");
            double mult = 1;
            if (s.endsWith("k")) mult = 1e3; else if (s.endsWith("m")) mult = 1e6;
            else if (s.endsWith("b")) mult = 1e9; else if (s.endsWith("t")) mult = 1e12;
            if (mult != 1) s = s.substring(0, s.length() - 1);
            double v = Double.parseDouble(s) * mult;
            if (v < 1 || v > 1e15) return -1;
            return (long) v;
        } catch (Exception e) { return -1; }
    }

    static String fmt(long n) { return String.format(Locale.GERMANY, "%,d", n); }

    // ---- Commands ----
    void registerCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("sell")
                .executes(c -> { Gui.openSell(this, c.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("hand").executes(c -> doSell(c, false)))
                .then(Commands.literal("all").executes(c -> doSell(c, true)))
                .then(Commands.literal("preview").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    ItemStack h = p.getMainHandItem();
                    long v = h.isEmpty() ? 0 : sell.preview(List.of(toStack(h)));
                    return msg(c, "§eVorschau: §a$" + fmt(v));
                })));

        d.register(Commands.literal("pay")
                .then(Commands.argument("spieler", EntityArgument.player())
                        .then(Commands.argument("betrag", StringArgumentType.word()).executes(c -> {
                            ServerPlayer from = c.getSource().getPlayerOrException();
                            ServerPlayer to = EntityArgument.getPlayer(c, "spieler");
                            long amt = parseAmount(StringArgumentType.getString(c, "betrag"));
                            if (amt <= 0) return msg(c, "§cUngültiger Betrag. Beispiele: 500, 1.5k, 2m");
                            if (from.getUUID().equals(to.getUUID())) return msg(c, "§cDu kannst dir nicht selbst Geld senden.");
                            if (!eco.take(from.getUUID(), amt))
                                return msg(c, "§cNicht genug Geld. Du hast $" + fmt(eco.get(from.getUUID())) + ".");
                            eco.add(to.getUUID(), amt);
                            to.sendSystemMessage(Component.literal("§a" + from.getName().getString() + " hat dir $" + fmt(amt) + " gesendet."));
                            return msg(c, "§aDu hast $" + fmt(amt) + " an " + to.getName().getString() + " gesendet.");
                        }))));

        d.register(Commands.literal("bal").executes(c -> msg(c,
                "§eKontostand: §a$" + fmt(eco.get(c.getSource().getPlayerOrException().getUUID())))));

        d.register(Commands.literal("meta").executes(c -> {
            StringBuilder sb = new StringBuilder("§6Aktuelle Metas:");
            meta.current().forEach((id, b) -> sb.append("\n§e").append(id).append(" §7(+").append(Math.round(b * 100)).append("%)"));
            return msg(c, sb.toString());
        }));

        d.register(Commands.literal("orders")
                .executes(c -> { Gui.openOrders(this, c.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("claim").executes(c -> { claim(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("create").then(Commands.argument("menge", IntegerArgumentType.integer(1, 100000))
                        .then(Commands.argument("preis", LongArgumentType.longArg(1)).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            ItemStack h = p.getMainHandItem();
                            if (h.isEmpty()) return msg(c, "§cNimm das gewünschte Item in die Hand.");
                            int amount = IntegerArgumentType.getInteger(c, "menge");
                            long ppi = LongArgumentType.getLong(c, "preis");
                            String id = BuiltInRegistries.ITEM.getKey(h.getItem()).toString();
                            Orders.Order o = orders.playerOrder(p.getUUID(), id, Map.of(), amount, ppi);
                            if (o == null) return msg(c, "§cNicht genug Geld. Du brauchst $" + fmt(ppi * amount) + ".");
                            return msg(c, "§aOrder #" + o.id + " erstellt: " + amount + "x " + h.getHoverName().getString()
                                    + " für je $" + fmt(ppi) + " (bezahlt: $" + fmt(ppi * amount) + ")");
                        })))));

        d.register(Commands.literal("ah")
                .executes(c -> { Gui.openAh(this, c.getSource().getPlayerOrException()); return 1; })
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
