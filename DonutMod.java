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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.commands.SharedSuggestionProvider;
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
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;

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

    static DonutMod INSTANCE;

    @Override
    public void onInitialize() {
        INSTANCE = this;
        for (Object[] row : KNOWN) prices.setBase("minecraft:" + row[0], ((Number) row[1]).longValue());
        prices.sellFactor = 6.0 / PriceFile.MULT;   // /sell zahlt 6x, Markt (AH) 48x des Originalpreises
        meta.roll(META_POOL, 2, RND);

        CommandRegistrationCallback.EVENT.register((d, ctx, env) -> registerCommands(d));
        Tools.register();
        Spawners.register();
        Hoppers.register();
        Homes.register();
        OrderBuilder.register(this);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> { srv = server; loadBalances(); loadNames(); initEnchants(); PriceFile.loadInto(prices); Custom.registerPrices(prices); initItems(); loadMarket(); market = new Market(this); market.init(); seedCustom(); market.ensureCustomOrders(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveBalances());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer jp = handler.getPlayer();
            names.put(jp.getUUID(), jp.getName().getString());
            createBoard(jp);
            if (market != null && market.reduced)
                Gui.say(jp, "§eMarkt: wegen wenig Arbeitsspeicher nur " + fmt(market.minAh) + " Angebote und " + fmt(market.minOrders)
                        + " Orders. Gib Minecraft im Launcher mehr RAM, dann wird er groesser.");
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> boards.remove(handler.getPlayer().getUUID()));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int t = server.getTickCount();
            if (market != null) market.tick(t);
            if (t % 20 == 0) updateBoards(server);          // jede Sekunde Anzeige aktualisieren
            if (t % 6000 == 0) saveBalances();              // alle 5 Min speichern
            if (t % 1200 == 0) botCycle(server);          // jede Minute
            if (t % 36000 == 0) {                           // alle 30 Min neue Meta
                meta.roll(META_POOL, 2, RND);
            }
        });
    }

    // ---- Speichern ----
    void saveBalances() {
        saveNames();
        saveMarket();
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
                Component.literal("§9§lDonutsmp Economy"), ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
        boards.put(p.getUUID(), obj);
        p.connection.send(new ClientboundSetObjectivePacket(obj, 0));                 // 0 = erstellen
        p.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, obj));
        sendMoney(p, obj);
    }

    void sendMoney(ServerPlayer p, Objective obj) {
        p.connection.send(new ClientboundSetScorePacket("money", obj.getName(), 1,
                Optional.of(Component.literal("§eGeld: §a$" + fmt(eco.get(p.getUUID())))), Optional.of(BlankFormat.INSTANCE)));
        p.connection.send(new ClientboundSetScorePacket("info", obj.getName(), 0,
                Optional.of(Component.literal("§9unser Donutsmp")), Optional.of(BlankFormat.INSTANCE)));
    }

    void updateBoards(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Objective obj = boards.get(p.getUUID());
            if (obj == null) createBoard(p); else sendMoney(p, obj);
        }
    }

    // ---- Bot-Logik ----
    static final long REQ_AH = 3_000_000L, REQ_ORDERS = 1_500_000L;   // Wunsch; wird bei wenig RAM automatisch verkleinert
    Market market;
    static final double BOT_ORDER_FILL = 0.01;   // Chance pro Minute, dass ein Bot-Order teilweise gefuellt wird
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

    final Map<Item, String> idCache = new HashMap<>();
    static final Map<Item, String> NAME_CACHE = new HashMap<>();
    final Map<String, Integer> capCache = new HashMap<>();
    final List<Holder.Reference<Enchantment>> enchList = new ArrayList<>();
    final Set<UUID> shulkerOn = new HashSet<>();
    /** Spielernamen (UUID -> Name), damit man /ah <Name> und /orders <Name> nutzen kann, auch fuer Offline-Spieler. */
    final Map<UUID, String> names = new HashMap<>();

    static Path marketFile() { return FabricLoader.getInstance().getConfigDir().resolve("donutmod-market.json"); }

    static final class SavedListing { String seller; long price; String item; }
    static final class SavedOrder { String owner; String itemId; Map<String, Integer> ench; int remaining; long ppi; }
    static final class SavedMarket {
        List<SavedListing> ah = new ArrayList<>();
        List<SavedOrder> orders = new ArrayList<>();
        Map<String, List<String>> storage = new HashMap<>();
    }

    String encodeStack(ItemStack st) {
        try {
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, srv.registryAccess());
            return ItemStack.CODEC.encodeStart(ops, st).result().map(Tag::toString).orElse(null);
        } catch (Exception e) { return null; }
    }

    ItemStack decodeStack(String s) {
        try {
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, srv.registryAccess());
            CompoundTag tag = TagParser.parseCompoundFully(s);
            return ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
        } catch (Exception e) { return ItemStack.EMPTY; }
    }

    /** Speichert Spieler-AH-Angebote, Spieler-Orders und das Lager (Bot-Eintraege entstehen beim Start neu). */
    void saveMarket() {
        if (srv == null) return;
        try {
            SavedMarket sm = new SavedMarket();
            for (AuctionHouse.Listing l : ah.all()) {
                if (l.seller == null) continue;
                ItemStack st = ahItems.get(l.id);
                String enc = st == null ? null : encodeStack(st);
                if (enc == null) continue;
                SavedListing x = new SavedListing();
                x.seller = l.seller.toString(); x.price = l.price; x.item = enc;
                sm.ah.add(x);
            }
            for (Orders.Order o : orders.all()) {
                if (o.owner == null) continue;
                SavedOrder x = new SavedOrder();
                x.owner = o.owner.toString(); x.itemId = o.itemId; x.ench = new HashMap<>(o.enchants);
                x.remaining = o.remaining; x.ppi = o.pricePerItem;
                sm.orders.add(x);
            }
            for (Map.Entry<UUID, List<ItemStack>> e : pending.entrySet()) {
                List<String> l = new ArrayList<>();
                for (ItemStack s : e.getValue()) { String enc = encodeStack(s); if (enc != null) l.add(enc); }
                if (!l.isEmpty()) sm.storage.put(e.getKey().toString(), l);
            }
            Files.writeString(marketFile(), GSON.toJson(sm));
        } catch (Exception e) { System.err.println("[DonutMod] Markt speichern fehlgeschlagen: " + e); }
    }

    void loadMarket() {
        try {
            if (!Files.exists(marketFile())) return;
            SavedMarket sm = GSON.fromJson(Files.readString(marketFile()), SavedMarket.class);
            if (sm == null) return;
            for (SavedListing x : sm.ah) {
                ItemStack st = decodeStack(x.item);
                if (st.isEmpty()) continue;
                AuctionHouse.Listing l = ah.list(UUID.fromString(x.seller), toStack(st), x.price);
                ahItems.put(l.id, st);
            }
            for (SavedOrder x : sm.orders)
                orders.restore(UUID.fromString(x.owner), x.itemId, x.ench == null ? new HashMap<>() : x.ench, x.remaining, x.ppi);
            for (Map.Entry<String, List<String>> e : sm.storage.entrySet()) {
                List<ItemStack> l = new ArrayList<>();
                for (String enc : e.getValue()) { ItemStack st = decodeStack(enc); if (!st.isEmpty()) l.add(st); }
                if (!l.isEmpty()) pending.put(UUID.fromString(e.getKey()), l);
            }
        } catch (Exception e) { System.err.println("[DonutMod] Markt laden fehlgeschlagen: " + e); }
    }

    static Path namesFile() { return FabricLoader.getInstance().getConfigDir().resolve("donutmod-names.json"); }

    String playerName(UUID u) { return u == null ? "Server" : names.getOrDefault(u, "Spieler"); }

    UUID uuidOfName(String n) {
        if (n == null || n.isBlank()) return null;
        for (Map.Entry<UUID, String> e : names.entrySet()) if (e.getValue().equalsIgnoreCase(n.trim())) return e.getKey();
        return null;
    }

    void saveNames() {
        try {
            Map<String, String> out = new HashMap<>();
            names.forEach((u, n) -> out.put(u.toString(), n));
            Files.writeString(namesFile(), GSON.toJson(out));
        } catch (Exception e) { System.err.println("[DonutMod] Namen speichern fehlgeschlagen: " + e); }
    }

    void loadNames() {
        try {
            if (!Files.exists(namesFile())) return;
            Map<String, String> in = GSON.fromJson(Files.readString(namesFile()), new TypeToken<Map<String, String>>() {}.getType());
            if (in != null) in.forEach((k, v) -> names.put(UUID.fromString(k), v));
        } catch (Exception e) { System.err.println("[DonutMod] Namen laden fehlgeschlagen: " + e); }
    }

    String idOf(Item it) { return idCache.computeIfAbsent(it, k -> BuiltInRegistries.ITEM.getKey(k).toString()); }

    static String idOfStatic(ItemStack st) {
        String c = Custom.idOf(st);
        return c != null ? c : BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
    }

    static String nameOf(Item it) {
        return NAME_CACHE.computeIfAbsent(it, k -> k.getDefaultInstance().getHoverName().getString().toLowerCase(Locale.ROOT));
    }

    static boolean isShulker(ItemStack st) { return !st.isEmpty() && idOfStatic(st).endsWith("shulker_box"); }

    static void ping(ServerPlayer p, SoundEvent s, float pitch) { p.level().playSound(null, p.blockPosition(), s, SoundSource.PLAYERS, 0.8f, pitch); }

    /** Wie oft ein Item gleichzeitig vorkommen darf: teure/seltene Items nur 1-60 mal (Drachenei: 1x). */
    int cap(String id) {
        return capCache.computeIfAbsent(id, k -> {
            long pr = prices.unitPrice(k, Map.of());
            return pr >= 1_000_000 ? 1 : pr >= 300_000 ? 3 : pr >= 100_000 ? 8 : pr >= 30_000 ? 60 : Integer.MAX_VALUE;
        });
    }

    void initEnchants() {
        enchList.clear();
        if (srv == null) return;
        srv.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements().forEach(enchList::add);
        // Jede Verzauberung ist ca. 10k (Stufe 1) bis 40k (Maximalstufe) wert, inkl. 25 % Aufschlag
        for (Holder.Reference<Enchantment> h : enchList) {
            int max = Math.max(1, h.value().getMaxLevel());
            prices.setEnchantValue(h.getRegisteredName(), Math.max(8_000L, Math.min(32_000L, 32_000L / max)));
        }
    }

    /** Item mit zufaelligen, passenden Verzauberungen (Buecher: 1-2). null, wenn nicht verzauberbar. */
    ItemStack randomEnchanted(Item it) {
        if (enchList.isEmpty()) return null;
        ItemStack st = new ItemStack(it);
        boolean book = it == Items.ENCHANTED_BOOK;
        if (!book && !st.isEnchantable()) return null;
        List<Holder.Reference<Enchantment>> ok = new ArrayList<>();
        for (Holder.Reference<Enchantment> h : enchList) if (book || h.value().isSupportedItem(st)) ok.add(h);
        if (ok.isEmpty()) return null;
        Collections.shuffle(ok, RND);
        int n = Math.min(ok.size(), 1 + RND.nextInt(book ? 2 : 4));
        for (int i = 0; i < n; i++) {
            Holder.Reference<Enchantment> h = ok.get(i);
            int max = Math.max(1, h.value().getMaxLevel());
            int lvl = RND.nextInt(3) == 0 ? max : 1 + RND.nextInt(max);
            st.enchant(h, lvl);
        }
        return st;
    }

    /** ItemStack aus Item-Id, Verzauberungen und Menge (fuer Anzeige und Lieferungen). */
    ItemStack buildStack(String itemId, Map<String, Integer> ench, int count) {
        ItemStack custom = Custom.make(itemId);
        if (custom != null) { custom.setCount(Math.max(1, count)); return custom; }
        Item it = ITEM_BY_ID.get(itemId);
        if (it == null) return ItemStack.EMPTY;
        ItemStack st = new ItemStack(it, Math.max(1, count));
        for (Map.Entry<String, Integer> e : ench.entrySet())
            for (Holder.Reference<Enchantment> h : enchList)
                if (h.getRegisteredName().equals(e.getKey())) { st.enchant(h, e.getValue()); break; }
        return st;
    }

    /** Immer mindestens 2 Angebote pro Shard-Werkzeug und Spawner (15-30 Mio. bzw. Spawner-Preise). */
    void seedCustom() {
        Map<String, Integer> have = new HashMap<>();
        for (ItemStack s : ahItems.values()) { String cid = Custom.idOf(s); if (cid != null) have.merge(cid, 1, Integer::sum); }
        for (Map.Entry<String, Long> e : Custom.BASE.entrySet()) {
            for (int i = have.getOrDefault(e.getKey(), 0); i < 2; i++) {
                ItemStack st = Custom.make(e.getKey());
                if (st == null) continue;
                long price = Math.round(e.getValue() * (0.85 + RND.nextDouble() * 0.3));
                AuctionHouse.Listing l = ah.list(null, toStack(st), price);
                ahItems.put(l.id, st);
            }
        }
    }

    void botCycle(MinecraftServer server) {
        for (AuctionHouse.Listing l : ah.botBuyPlayerListings(RND)) {
            ItemStack st = ahItems.remove(l.id);
            if (market != null) market.ahRemoved(l);
            if (l.seller != null) {
                ServerPlayer p = server.getPlayerList().getPlayer(l.seller);
                if (p != null) {
                    p.sendSystemMessage(Component.literal("§a[AH] Dein Angebot #" + l.id
                            + (st != null ? " (" + st.getHoverName().getString() + ")" : "") + " wurde fuer $" + fmt(l.price) + " verkauft."));
                    ping(p, SoundEvents.PLAYER_LEVELUP, 1.0f);
                }
            }
        }
        prices.decay();
        if (market != null) market.popDecay();
        // Spieler-Orders: Bots liefern langsam, in kleinen Portionen
        List<Orders.Order> mine = new ArrayList<>();
        for (Orders.Order o : orders.all()) if (o.owner != null) mine.add(o);
        for (Orders.Order o : mine) {
            ItemStack probe = buildStack(o.itemId, o.enchants, 1);
            if (probe.isEmpty()) continue;
            double ratio = o.pricePerItem / Math.max(1.0, (double) prices.sellUnitPrice(o.itemId, o.enchants));
            double chance = Math.min(0.8, 0.05 * Math.pow(ratio, 3));
            if (RND.nextDouble() < chance) {
                int n = (int) Math.max(1, Math.min(2000, Math.ceil(o.remaining * (0.01 + RND.nextDouble() * 0.02))));
                int d = orders.botDeliver(o, n);
                if (d > 0) addPending(o.owner, buildStack(o.itemId, o.enchants, d));
            }
        }
        seedCustom();
        if (market != null) market.ensureCustomOrders();
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
            if (p != null) ping(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.3f);
        }
    }

    int pendingCount(UUID u) {
        int n = 0;
        for (ItemStack s : pending.getOrDefault(u, List.of())) n += s.getCount();
        return n;
    }

    void claim(ServerPlayer p) { Gui.openStorage(this, p); }

    /** Beim Beenden: offene Spieler-Orders zurueckzahlen, damit kein Geld verloren geht. */
    void refundOrders() {
        for (Orders.Order o : new ArrayList<>(orders.all())) if (o.owner != null) orders.cancel(o);
    }

    // ---- Umrechnung MC <-> Core ----
    static Stack toStack(ItemStack st) {
        String id = idOfStatic(st);
        Map<String, Integer> ench = new HashMap<>();
        for (ItemEnchantments ie : new ItemEnchantments[]{st.get(DataComponents.ENCHANTMENTS), st.get(DataComponents.STORED_ENCHANTMENTS)})
            if (ie != null)
                for (Object2IntMap.Entry<net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>> e : ie.entrySet())
                    ench.merge(e.getKey().getRegisteredName(), e.getIntValue(), Math::max);
        return new Stack(id, st.getCount(), ench);
    }

    /** Fuegt den Stack und - bei Shulkern - auch seinen Inhalt zur Verkaufsliste hinzu. */
    static void addStacks(ItemStack st, List<Stack> out) {
        out.add(toStack(st));
        if (isShulker(st)) {
            ItemContainerContents cc = st.get(DataComponents.CONTAINER);
            if (cc != null) for (ItemStack in : cc.nonEmptyItems()) if (!in.isEmpty()) out.add(toStack(in));
        }
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
                    List<Stack> pv = new ArrayList<>();
                    if (!h.isEmpty()) addStacks(h, pv);
                    long v = pv.isEmpty() ? 0 : sell.preview(pv);
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

        // OP-Check wie bei Vanilla-Befehlen (nutzt die Anforderung von /gamemode, funktioniert in jeder Version)
        com.mojang.brigadier.tree.CommandNode<CommandSourceStack> gm = d.getRoot().getChild("gamemode");
        java.util.function.Predicate<CommandSourceStack> isOp = gm != null ? gm.getRequirement() : (src -> false);
        // /money <Spieler> add|set|remove|weniger <Betrag>   (nur OP)
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> moneyCmd = Commands.literal("money").requires(isOp);
        com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> who = Commands.argument("spieler", StringArgumentType.word())
                .suggests((ctx, sb) -> SharedSuggestionProvider.suggest(names.values(), sb));
        for (String op : new String[]{"add", "set", "remove", "weniger"}) {
            String kind = op.equals("weniger") ? "remove" : op;
            who.then(Commands.literal(op).then(Commands.argument("betrag", StringArgumentType.word()).executes(c -> money(c, kind))));
        }
        d.register(moneyCmd.then(who));

        // /worth [Item]  zeigt den Wert
        d.register(Commands.literal("worth")
                .executes(c -> worth(c, null))
                .then(Commands.argument("item", StringArgumentType.greedyString())
                        .executes(c -> worth(c, StringArgumentType.getString(c, "item")))));

        d.register(Commands.literal("bal").executes(c -> msg(c,
                "§eKontostand: §a$" + fmt(eco.get(c.getSource().getPlayerOrException().getUUID())))));

        d.register(Commands.literal("meta").executes(c -> {
            StringBuilder sb = new StringBuilder("§6Aktuelle Metas:");
            meta.current().forEach((id, b) -> sb.append("\n§e").append(id).append(" §7(+").append(Math.round(b * 100)).append("%)"));
            return msg(c, sb.toString());
        }));

        d.register(ordersCmd("orders"));
        d.register(ordersCmd("order"));

        d.register(Commands.literal("ah")
                .executes(c -> { Gui.openAh(this, c.getSource().getPlayerOrException(), ""); return 1; })
                .then(Commands.argument("suche", StringArgumentType.greedyString())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), b)).executes(c -> {
                    Gui.openAh(this, c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "suche"));
                    return 1;
                }))
                .then(Commands.literal("sell").then(Commands.argument("preis", StringArgumentType.word())
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            ItemStack h = p.getMainHandItem();
                            if (h.isEmpty()) return msg(c, "§cDu hast nichts in der Hand.");
                            long price = parseAmount(StringArgumentType.getString(c, "preis"));
                            if (price <= 0) return msg(c, "§cUngültiger Preis. Beispiele: 500, 1.5k, 2m, 3b, 1t");
                            ItemStack copy = h.copy();
                            AuctionHouse.Listing l = ah.list(p.getUUID(), toStack(copy), price);
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

    LiteralArgumentBuilder<CommandSourceStack> ordersCmd(String name) {
        return Commands.literal(name)
                .executes(c -> { Gui.openOrders(this, c.getSource().getPlayerOrException(), ""); return 1; })
                .then(Commands.literal("claim").executes(c -> { claim(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("create").then(Commands.argument("menge", StringArgumentType.word())
                        .then(Commands.argument("preis", StringArgumentType.word()).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            ItemStack h = p.getMainHandItem();
                            if (h.isEmpty()) return msg(c, "§cNimm das gewünschte Item in die Hand.");
                            long am = parseAmount(StringArgumentType.getString(c, "menge"));
                            long ppi = parseAmount(StringArgumentType.getString(c, "preis"));
                            if (am <= 0 || am > 1_000_000_000L) return msg(c, "§cUngültige Menge (1 bis 1b). Beispiele: 64, 100k, 2m");
                            if (ppi <= 0) return msg(c, "§cUngültiger Preis. Beispiele: 500, 1.5k, 2m, 3b, 1t");
                            if (ppi > Long.MAX_VALUE / am) return msg(c, "§cGesamtpreis zu hoch.");
                            int amount = (int) am;
                            String id = toStack(h).id();
                            Orders.Order o = orders.playerOrder(p.getUUID(), id, toStack(h).enchants(), amount, ppi);
                            if (o == null) return msg(c, "§cNicht genug Geld. Du brauchst $" + fmt(ppi * amount) + ".");
                            return msg(c, "§aOrder #" + o.id + " erstellt: " + amount + "x " + h.getHoverName().getString()
                                    + " für je $" + fmt(ppi) + " (bezahlt: $" + fmt(ppi * amount) + ")");
                        }))))
                .then(Commands.argument("suche", StringArgumentType.greedyString())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), b)).executes(c -> {
                    Gui.openOrders(this, c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "suche"));
                    return 1;
                }));
    }

    int money(CommandContext<CommandSourceStack> c, String op) {
        String n = StringArgumentType.getString(c, "spieler");
        UUID u = uuidOfName(n);
        if (u == null) return msg(c, "§cSpieler unbekannt (muss schon einmal beigetreten sein): " + n);
        String raw = StringArgumentType.getString(c, "betrag");
        long amt = raw.equals("0") ? 0 : parseAmount(raw);
        if (amt < 0 || (amt == 0 && !op.equals("set"))) return msg(c, "§cUngültiger Betrag. Beispiele: 500, 1.5k, 2m, 3b, 1t");
        long cur = eco.get(u);
        switch (op) {
            case "add" -> eco.add(u, amt);
            case "remove" -> eco.take(u, Math.min(amt, cur));
            default -> { if (amt >= cur) eco.add(u, amt - cur); else eco.take(u, cur - amt); }
        }
        ServerPlayer on = srv == null ? null : srv.getPlayerList().getPlayer(u);
        if (on != null) on.sendSystemMessage(Component.literal("§eDein Kontostand: §a$" + fmt(eco.get(u))));
        return msg(c, "§aKontostand von " + names.getOrDefault(u, n) + ": $" + fmt(eco.get(u)));
    }

    int worth(CommandContext<CommandSourceStack> c, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        List<Stack> list = new ArrayList<>();
        String label;
        if (name == null || name.isBlank() || name.equalsIgnoreCase("item") || name.equalsIgnoreCase("hand")) {
            ItemStack h = p.getMainHandItem();
            if (h.isEmpty()) return msg(c, "§cNimm ein Item in die Hand oder nutze /worth <Name>, z.B. /worth diamond.");
            addStacks(h, list);
            label = h.getHoverName().getString() + " x" + h.getCount();
        } else {
            String q = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            Item found = null;
            for (Item it : pool) { String id = idOf(it); if (id.substring(id.indexOf(':') + 1).equals(q)) { found = it; break; } }
            if (found == null) for (Item it : pool) if (idOf(it).contains(q)) { found = it; break; }
            if (found == null) return msg(c, "§cKein Item gefunden: " + name);
            list.add(new Stack(idOf(found), 1, Map.of()));
            label = new ItemStack(found).getHoverName().getString() + " x1";
        }
        long sell = 0, market = 0;
        for (Stack s : list) { sell += prices.sellStackPrice(s); market += prices.stackPrice(s); }
        double f = prices.factorOf(list.get(0).id());
        return msg(c, "§6Wert von §f" + label + "\n§7Sell (/sell): §a$" + fmt(sell) + "\n§7AH-Markt: §6$" + fmt(market)
                + "\n§7Nachfrage-Faktor: §f" + String.format(Locale.ROOT, "%.2f", f) + "x");
    }

    int doSell(CommandContext<CommandSourceStack> c, boolean all) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        List<Stack> stacks = new ArrayList<>();
        if (all) {
            // nur Inventar + Hotbar (Slots 0-35), keine Ruestung und keine Zweithand
            var inv = p.getInventory();
            for (int i = 0; i < 36; i++) {
                ItemStack st = inv.getItem(i);
                if (!st.isEmpty()) { addStacks(st, stacks); inv.setItem(i, ItemStack.EMPTY); }
            }
        } else {
            ItemStack h = p.getMainHandItem();
            if (!h.isEmpty()) { addStacks(h, stacks); p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); }
        }
        if (stacks.isEmpty()) return msg(c, "§cNichts zu verkaufen.");
        long pay = sell.sell(p.getUUID(), stacks).payout();
        ping(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f);
        return msg(c, "§aVerkauft für $" + fmt(pay) + " §7| Kontostand: $" + fmt(eco.get(p.getUUID())));
    }

    static int msg(CommandContext<CommandSourceStack> c, String s) {
        c.getSource().sendSuccess(() -> Component.literal(s), false);
        return 1;
    }
}
