package de.donutmod.fabric;

import com.google.gson.Gson;
import de.donutmod.core.Stack;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Platzierte Spawner sammeln Items (statt Mobs). Rechtsklick oeffnet ein Menue. Abbauen nur mit Silk Touch. */
public final class Spawners {
    private Spawners() {}

    static final class Sp {
        String dim; int x, y, z; String type; String owner;
        int stack = 1;
        Map<String, Long> store = new LinkedHashMap<>();
    }

    static final class Pend {
        final Level level; final BlockPos pos; final String type; final UUID owner; int age = 0;
        Pend(Level level, BlockPos pos, String type, UUID owner) { this.level = level; this.pos = pos; this.type = type; this.owner = owner; }
    }

    static final Map<String, Sp> MAP = new HashMap<>();
    static final List<Pend> PENDING = new ArrayList<>();
    static final Gson GSON = new Gson();
    static final long CAP = 64L * 500;
    static final int MAX_STACK = 1000;

    static final Map<String, Object[][]> DROPS = new HashMap<>();
    static {
        DROPS.put("skeleton", new Object[][]{{"minecraft:bone", 4}, {"minecraft:arrow", 4}});
        DROPS.put("zombified_piglin", new Object[][]{{"minecraft:gold_nugget", 5}, {"minecraft:gold_ingot", 1}, {"minecraft:rotten_flesh", 4}});
        DROPS.put("creeper", new Object[][]{{"minecraft:gunpowder", 4}});
        DROPS.put("iron_golem", new Object[][]{{"minecraft:iron_ingot", 3}, {"minecraft:poppy", 1}});
        DROPS.put("zombie", new Object[][]{{"minecraft:rotten_flesh", 5}, {"minecraft:carrot", 1}});
        DROPS.put("spider", new Object[][]{{"minecraft:string", 4}, {"minecraft:spider_eye", 1}});
    }

    static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("donutmod-spawners.json"); }
    static String key(String dim, int x, int y, int z) { return dim + "|" + x + "|" + y + "|" + z; }
    static String key(Level l, BlockPos p) { return key(l.dimension().toString(), p.getX(), p.getY(), p.getZ()); }

    static boolean silk(ItemStack tool) {
        DonutMod m = DonutMod.INSTANCE;
        ItemEnchantments ie = tool.get(DataComponents.ENCHANTMENTS);
        if (m == null || ie == null) return false;
        for (var h : m.enchList)
            if (h.getRegisteredName().equals("minecraft:silk_touch") && ie.getLevel(h) > 0) return true;
        return false;
    }

    static String detect(ServerLevel level, BlockEntity be) {
        String s = be == null ? "" : be.saveWithoutMetadata(level.registryAccess()).toString();
        if (s.contains("minecraft:zombified_piglin")) return "zombified_piglin";
        if (s.contains("minecraft:skeleton")) return "skeleton";
        if (s.contains("minecraft:creeper")) return "creeper";
        if (s.contains("minecraft:iron_golem")) return "iron_golem";
        if (s.contains("minecraft:spider") || s.contains("minecraft:cave_spider")) return "spider";
        return "zombie";
    }

    static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide()) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            ItemStack held = player.getItemInHand(hand);
            String hk = Custom.key(held);
            boolean heldSpawner = hk != null && hk.startsWith("donutmod_spawner_");
            Sp sp = MAP.get(key(world, pos));
            if (sp != null && world.getBlockState(pos).is(Blocks.SPAWNER) && !player.isShiftKeyDown() && player instanceof ServerPlayer p) {
                if (heldSpawner) {
                    String type = hk.substring("donutmod_spawner_".length());
                    if (!type.equals(sp.type)) { Gui.say(p, "§cDas ist ein anderer Spawner-Typ."); return InteractionResult.SUCCESS; }
                    if (sp.stack >= MAX_STACK) { Gui.say(p, "§cMaximal " + MAX_STACK + " Spawner pro Stapel."); return InteractionResult.SUCCESS; }
                    int add = Math.min(held.getCount(), MAX_STACK - sp.stack);
                    held.shrink(add);
                    sp.stack += add;
                    Gui.say(p, "§aSpawner gestapelt: §f" + sp.stack + "x");
                    return InteractionResult.SUCCESS;
                }
                openMenu(p, sp);
                return InteractionResult.SUCCESS;
            }
            if (heldSpawner)
                PENDING.add(new Pend(world, pos.relative(hit.getDirection()), hk.substring("donutmod_spawner_".length()), player.getUUID()));
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (!state.is(Blocks.SPAWNER) || world.isClientSide() || silk(player.getMainHandItem())) return true;
            if (player instanceof ServerPlayer sp) Gui.say(sp, "§cSpawner lassen sich nur mit Silk Touch abbauen.");
            return false;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (!state.is(Blocks.SPAWNER) || !(world instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)) return;
            Sp reg = MAP.get(key(level, pos));
            if (reg != null && reg.stack > 1) {
                reg.stack--;
                level.setBlock(pos, Blocks.SPAWNER.defaultBlockState(), 3);
                Block.popResource(level, pos, Custom.make("donutmod:spawner_" + reg.type));
                Gui.say(sp, "§eSpawner-Stapel: noch " + reg.stack + "x");
                return;
            }
            if (reg != null) MAP.remove(key(level, pos));
            String type = reg != null ? reg.type : detect(level, be);
            if (reg != null) {
                for (Map.Entry<String, Long> e : reg.store.entrySet()) {
                    Item it = DonutMod.ITEM_BY_ID.get(e.getKey());
                    if (it != null && e.getValue() > 0) DonutMod.INSTANCE.addPending(sp.getUUID(), new ItemStack(it, (int) Math.min(e.getValue(), 1_000_000L)));
                }
            }
            ItemStack drop = Custom.make("donutmod:spawner_" + type);
            if (drop != null) Block.popResource(level, pos, drop);
        });

        // Platzierte Spawner erzeugen vanilla Schweine - die werden entfernt
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity.getType() != EntityType.PIG || MAP.isEmpty()) return;
            String dim = world.dimension().toString();
            BlockPos b = entity.blockPosition();
            for (Sp s : MAP.values())
                if (s.dim.equals(dim) && Math.abs(s.x - b.getX()) <= 6 && Math.abs(s.y - b.getY()) <= 6 && Math.abs(s.z - b.getZ()) <= 6) {
                    entity.discard();
                    return;
                }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (Iterator<Pend> it = PENDING.iterator(); it.hasNext(); ) {
                Pend p = it.next();
                p.age++;
                if (p.age < 2) continue;
                String k = key(p.level, p.pos);
                if (p.level.getBlockState(p.pos).is(Blocks.SPAWNER) && !MAP.containsKey(k)) {
                    Sp s = new Sp();
                    s.dim = p.level.dimension().toString();
                    s.x = p.pos.getX(); s.y = p.pos.getY(); s.z = p.pos.getZ();
                    s.type = p.type; s.owner = p.owner.toString();
                    MAP.put(k, s);
                    it.remove();
                } else if (p.age > 20) it.remove();
            }
            if (server.getTickCount() % 1200 == 0) generate();
            if (server.getTickCount() % 6000 == 0) save();
        });

        ServerLifecycleEvents.SERVER_STARTED.register(s -> load());
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> save());
    }

    static void generate() {
        for (Sp s : MAP.values()) {
            Object[][] d = DROPS.get(s.type);
            if (d == null) continue;
            for (Object[] row : d) {
                long n = Math.max(0, Math.round(((Number) row[1]).doubleValue() * (0.5 + DonutMod.RND.nextDouble()))) * s.stack;
                if (n == 0) continue;
                long cur = s.store.getOrDefault((String) row[0], 0L);
                s.store.put((String) row[0], Math.min(CAP * s.stack, cur + n));
            }
        }
    }

    static void save() {
        try { Files.writeString(file(), GSON.toJson(new ArrayList<>(MAP.values()))); }
        catch (Exception e) { System.err.println("[DonutMod] Spawner speichern fehlgeschlagen: " + e); }
    }

    static void load() {
        try {
            if (!Files.exists(file())) return;
            Sp[] arr = GSON.fromJson(Files.readString(file()), Sp[].class);
            MAP.clear();
            if (arr != null) for (Sp s : arr) if (s != null && s.dim != null) MAP.put(key(s.dim, s.x, s.y, s.z), s);
        } catch (Exception e) { System.err.println("[DonutMod] Spawner laden fehlgeschlagen: " + e); }
    }

    // ---------- Menue ----------
    static void dropItem(ServerPlayer p, Sp s, String id) {
        Item it = DonutMod.ITEM_BY_ID.get(id);
        long have = s.store.getOrDefault(id, 0L);
        if (it == null || have <= 0) return;
        int max = Math.max(1, it.getDefaultInstance().getMaxStackSize());
        int stacks = 0;
        while (have > 0 && stacks < 40) {
            int n = (int) Math.min(have, max);
            p.drop(new ItemStack(it, n), false);
            have -= n;
            stacks++;
        }
        if (have <= 0) s.store.remove(id); else s.store.put(id, have);
    }

    static void openMenu(ServerPlayer p, Sp s) {
        DonutMod m = DonutMod.INSTANCE;
        Gui.ListGui<Map.Entry<String, Long>> g = new Gui.ListGui<>(m, p, Custom.pretty(s.type) + " Spawner",
                () -> new ArrayList<>(s.store.entrySet()),
                e -> {
                    Item it = DonutMod.ITEM_BY_ID.get(e.getKey());
                    if (it == null) return Gui.named(Items.BARRIER, "§c" + e.getKey());
                    return Gui.withLore(new ItemStack(it, (int) Math.max(1, Math.min(64, e.getValue()))),
                            "§7Gelagert: §f" + e.getValue(), "", "§aKlick: dieses Item droppen");
                },
                (e, pl) -> dropItem(pl, s, e.getKey()));
        g.extra(47, () -> Gui.named(Items.DROPPER, "§6Seite droppen", "§7Droppt alle Items dieser Seite"), () -> {
            for (Map.Entry<String, Long> e : g.pageItems()) dropItem(p, s, e.getKey());
        });
        g.extra(48, () -> Gui.named(Items.TNT, "§cAlles droppen", "§7Droppt alles (max. 40 Stacks pro Item pro Klick)"), () -> {
            for (String id : new ArrayList<>(s.store.keySet())) dropItem(p, s, id);
        });
        g.extra(50, () -> Gui.named(Items.EMERALD, "§aAlles verkaufen", "§7Verkauft alles zum Sell-Preis"), () -> {
            List<Stack> list = new ArrayList<>();
            for (Map.Entry<String, Long> e : s.store.entrySet())
                if (e.getValue() > 0) list.add(new Stack(e.getKey(), (int) Math.min(e.getValue(), Integer.MAX_VALUE), Map.of()));
            if (list.isEmpty()) { Gui.say(p, "§7Nichts zu verkaufen."); return; }
            long pay = m.sell.sell(p.getUUID(), list).payout();
            s.store.clear();
            DonutMod.ping(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f);
            Gui.say(p, "§aVerkauft für $" + DonutMod.fmt(pay));
        });
        g.extra(52, () -> Gui.named(Items.SPAWNER, "§6" + Custom.pretty(s.type) + " Spawner",
                "§7Gestapelt: §f" + s.stack + "x", "§7Sammelt pro Minute Items", "§7Abbauen: Silk Touch"), () -> { });
        g.open();
    }
}
