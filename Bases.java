package de.donutmod.fabric;

import com.google.gson.Gson;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Zufaellige unterirdische Basen (Farmen und Stashes) tief unter y = -8, ca. alle 1500-2000 Bloecke.
 * Entstehen, sobald ein Spieler in die Naehe kommt. Eigene Bauplaene (keine Schematics aus dem Internet).
 */
public final class Bases {
    private Bases() {}

    static final int SPACING = 1750, JITTER = 250;   // Abstand zwischen Nachbarn: 1500 bis 2000 Bloecke
    static final Set<String> DONE = new HashSet<>();
    static final Gson GSON = new Gson();

    static final BlockState WALL = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
    static final BlockState FLOOR = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
    static final BlockState AIR = Blocks.AIR.defaultBlockState();

    static Path file() { return DonutMod.worldDir().resolve("donutmod-bases.json"); }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int t = server.getTickCount();
            if (t % 40 == 0) scan(server);
            if (t % 6000 == 0) save();
        });
        ServerLifecycleEvents.SERVER_STARTED.register(s -> load());
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> save());
        CommandRegistrationCallback.EVENT.register((d, ctx, env) -> {
            var gm = d.getRoot().getChild("gamemode");
            java.util.function.Predicate<CommandSourceStack> isOp = gm != null ? gm.getRequirement() : (src -> false);
            d.register(Commands.literal("nearbase").requires(isOp).executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                int ci = Math.floorDiv((int) p.getX(), SPACING), cj = Math.floorDiv((int) p.getZ(), SPACING);
                StringBuilder sb = new StringBuilder("§6Basen in der Nähe (unter y=-8):");
                for (int di = -1; di <= 1; di++)
                    for (int dj = -1; dj <= 1; dj++) {
                        int[] pos = cellPos(((ServerLevel) p.level()).getSeed(), ci + di, cj + dj);
                        sb.append("\n§e").append(pos[0]).append(" ").append(pos[1]).append(DONE.contains((ci + di) + "," + (cj + dj)) ? " §7(schon gebaut)" : " §a(noch nicht erzeugt)");
                    }
                return DonutMod.msg(c, sb.toString());
            }));
        });
    }

    static void save() {
        try { Files.writeString(file(), GSON.toJson(new ArrayList<>(DONE))); }
        catch (Exception e) { System.err.println("[DonutMod] Basen speichern fehlgeschlagen: " + e); }
    }

    static void load() {
        DONE.clear();
        try {
            if (!Files.exists(file())) return;
            String[] arr = GSON.fromJson(Files.readString(file()), String[].class);
            DONE.clear();
            if (arr != null) Collections.addAll(DONE, arr);
        } catch (Exception e) { System.err.println("[DonutMod] Basen laden fehlgeschlagen: " + e); }
    }

    static Random cellRandom(long seed, int i, int j) { return new Random(seed * 31L + i * 341873128712L + j * 132897987541L); }

    static int[] cellPos(long seed, int i, int j) {
        Random r = cellRandom(seed, i, j);
        return new int[]{i * SPACING + r.nextInt(JITTER + 1), j * SPACING + r.nextInt(JITTER + 1)};
    }

    static boolean chunksLoaded(ServerLevel level, int bx, int bz) {
        for (int cx = (bx >> 4) - 1; cx <= (bx >> 4) + 2; cx++)
            for (int cz = (bz >> 4) - 1; cz <= (bz >> 4) + 2; cz++)
                if (!level.hasChunk(cx, cz)) return false;
        return true;
    }

    static void scan(MinecraftServer server) {
        DonutMod m = DonutMod.INSTANCE;
        if (m == null || m.enchList.isEmpty()) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Level lv = p.level();
            if (!(lv instanceof ServerLevel level) || !level.dimension().equals(Level.OVERWORLD)) continue;
            int ci = Math.floorDiv((int) p.getX(), SPACING), cj = Math.floorDiv((int) p.getZ(), SPACING);
            for (int di = -1; di <= 1; di++)
                for (int dj = -1; dj <= 1; dj++) {
                    int i = ci + di, j = cj + dj;
                    String key = i + "," + j;
                    if (DONE.contains(key)) continue;
                    int[] pos = cellPos(level.getSeed(), i, j);
                    double dx = pos[0] - p.getX(), dz = pos[1] - p.getZ();
                    if (dx * dx + dz * dz > 90.0 * 90.0 || !chunksLoaded(level, pos[0], pos[1])) continue;
                    DONE.add(key);
                    try { generate(m, level, cellRandom(level.getSeed() + 7, i, j), pos[0], pos[1]); }
                    catch (Exception e) { System.err.println("[DonutMod] Basis-Bau fehlgeschlagen: " + e); }
                }
        }
    }

    // ---------- Bauen ----------
    static void fill(ServerLevel l, int x1, int y1, int z1, int x2, int y2, int z2, BlockState st) {
        for (int x = x1; x <= x2; x++)
            for (int y = y1; y <= y2; y++)
                for (int z = z1; z <= z2; z++) l.setBlock(new BlockPos(x, y, z), st, 2);
    }

    static boolean hasFluid(ServerLevel l, int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = x1; x <= x2; x++)
            for (int y = y1; y <= y2; y++)
                for (int z = z1; z <= z2; z++)
                    if (!l.getBlockState(new BlockPos(x, y, z)).getFluidState().isEmpty()) return true;
        return false;
    }

    static void generate(DonutMod m, ServerLevel level, Random r, int bx, int bz) {
        boolean farm = r.nextInt(100) < 40;
        boolean rare = !farm && r.nextInt(100) < 15;
        int sx = farm ? 25 : 21, sz = farm ? 21 : 15;   // deutlich groessere Basen
        for (int attempt = 0; attempt < 16; attempt++) {
            int y0 = -46 + r.nextInt(27);   // Boden zwischen y=-46 und y=-20, also immer unter -8
            // keine Fluessigkeit (Wasser/Lava) im Bereich inkl. 2 Bloecken Rand
            if (hasFluid(level, bx - 2, y0 - 2, bz - 2, bx + sx + 1, y0 + 7, bz + sz + 1)) continue;
            if (farm) buildFarm(m, level, r, bx, y0, bz, sx, sz);
            else buildStash(m, level, r, bx, y0, bz, sx, sz, rare);
            return;
        }
    }

    static void room(ServerLevel level, int x0, int y0, int z0, int sx, int sz) {
        fill(level, x0 - 1, y0, z0 - 1, x0 + sx, y0 + 5, z0 + sz, WALL);         // Huelle
        fill(level, x0, y0 + 1, z0, x0 + sx - 1, y0 + 4, z0 + sz - 1, AIR);       // Innenraum
        fill(level, x0, y0, z0, x0 + sx - 1, y0, z0 + sz - 1, FLOOR);              // Boden
        BlockState lantern = Blocks.LANTERN.defaultBlockState();
        for (int[] c : new int[][]{{0, 0}, {sx - 1, 0}, {0, sz - 1}, {sx - 1, sz - 1}})
            level.setBlock(new BlockPos(x0 + c[0], y0 + 1, z0 + c[1]), lantern, 2);
    }

    static BlockPos placeSingle(ServerLevel level, BlockPos p, Direction facing) {
        level.setBlock(p, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), 2);
        return p;
    }

    static List<BlockPos> placeDouble(ServerLevel level, BlockPos left, Direction facing) {
        BlockPos right = left.relative(facing.getClockWise());
        level.setBlock(left, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, ChestType.LEFT), 2);
        level.setBlock(right, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
        return List.of(left, right);
    }

    /** Normale Basen: 1-20 Spawner gestapelt; in ca. 15 % der Basen ein Riesen-Stapel mit 1000-2000 Spawnern. */
    static int stackFor(Random r) { return r.nextInt(100) < 15 ? 1000 + r.nextInt(1001) : 1 + r.nextInt(20); }

    static void spawner(ServerLevel level, BlockPos p, String type, Random r) {
        level.setBlock(p, Blocks.SPAWNER.defaultBlockState(), 2);
        Spawners.registerAt(level, p, type, stackFor(r));
    }

    static void buildStash(DonutMod m, ServerLevel level, Random r, int x0, int y0, int z0, int sx, int sz, boolean rare) {
        room(level, x0, y0, z0, sx, sz);
        // Saeulen als Deko
        for (int x = x0 + 4; x < x0 + sx - 2; x += 4)
            for (int z : new int[]{z0 + 5, z0 + 9}) fill(level, x, y0 + 1, z, x, y0 + 4, z, WALL);
        List<BlockPos> chests = new ArrayList<>();
        if (rare) {
            for (int i = 0; i < 4; i++) chests.addAll(placeDouble(level, new BlockPos(x0 + 3 + i * 5, y0 + 1, z0), Direction.SOUTH));
        } else {
            for (int i = 0; i < 10; i++) {
                chests.add(placeSingle(level, new BlockPos(x0 + 1 + i * 2, y0 + 1, z0), Direction.SOUTH));
                chests.add(placeSingle(level, new BlockPos(x0 + 1 + i * 2, y0 + 1, z0 + sz - 1), Direction.NORTH));
            }
        }
        String[] types = {"skeleton", "zombified_piglin", "iron_golem", "creeper", "zombie", "spider"};
        int spawners = 2 + r.nextInt(3);   // 2 bis 4 Spawner
        for (int i = 0; i < spawners; i++)
            spawner(level, new BlockPos(x0 + sx - 2, y0 + 1, z0 + 3 + i * 3), types[r.nextInt(types.length)], r);
        List<ItemStack> items = new ArrayList<>();
        int lists = rare ? 6 : 3;   // viel mehr Loot als vorher
        for (int i = 0; i < lists; i++) items.addAll(loot(m, pickLot(r), r));
        distribute(level, chests, items, r);
        if (rare) fillRest(level, chests, r);
    }

    /** Grosse Redstone-Farm: 4 Reihen mit Kolben, Observern und Leitungen, Hopper-Baender mit Kisten, Spawner und Loot hinten. */
    static void buildFarm(DonutMod m, ServerLevel level, Random r, int x0, int y0, int z0, int sx, int sz) {
        room(level, x0, y0, z0, sx, sz);
        BlockState[] crops = {((CropBlock) Blocks.WHEAT).getStateForAge(7), ((CropBlock) Blocks.CARROTS).getStateForAge(7),
                ((CropBlock) Blocks.POTATOES).getStateForAge(7)};
        List<BlockPos> collectors = new ArrayList<>();
        for (int pair = 0; pair < 2; pair++) {
            int base = z0 + 1 + pair * 10;
            for (int strip = 0; strip < 2; strip++) {
                int a = base + strip * 5;
                BlockState crop = crops[r.nextInt(crops.length)];
                for (int x = x0 + 1; x <= x0 + sx - 2; x++) {
                    level.setBlock(new BlockPos(x, y0 + 1, a), Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH), 2);
                    level.setBlock(new BlockPos(x, y0, a + 1), Blocks.FARMLAND.defaultBlockState(), 2);
                    level.setBlock(new BlockPos(x, y0 + 1, a + 1), crop, 2);
                    level.setBlock(new BlockPos(x, y0 + 1, a + 2), Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.NORTH), 2);
                    level.setBlock(new BlockPos(x, y0 + 1, a + 3), Blocks.REDSTONE_WIRE.defaultBlockState(), 2);
                }
                level.setBlock(new BlockPos(x0, y0 + 1, a + 3), Blocks.REDSTONE_BLOCK.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x0 + sx - 1, y0 + 1, a + 3), Blocks.REDSTONE_LAMP.defaultBlockState(), 2);
            }
            // Sammelband: Hopper-Reihe fuehrt zu einer Kiste
            for (int x = x0 + 1; x <= x0 + sx - 3; x++)
                level.setBlock(new BlockPos(x, y0 + 1, base + 4), Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.FACING_HOPPER, Direction.EAST), 2);
            collectors.add(placeSingle(level, new BlockPos(x0 + sx - 2, y0 + 1, base + 4), Direction.WEST));
        }
        for (int x = x0 + 2; x < x0 + sx - 1; x += 4)
            for (int z : new int[]{z0 + 3, z0 + 8, z0 + 13, z0 + 18})
                level.setBlock(new BlockPos(x, y0 + 5, z), Blocks.GLOWSTONE.defaultBlockState(), 2);
        distribute(level, collectors, List.of(new ItemStack(Items.WHEAT, 32 + r.nextInt(33)), new ItemStack(Items.CARROT, 32 + r.nextInt(33)),
                new ItemStack(Items.POTATO, 32 + r.nextInt(33)), new ItemStack(Items.BONE_MEAL, 16 + r.nextInt(49))), r);
        // Spawner (teils Riesen-Stapel) und Loot an der Rueckwand
        String[] types = {"zombie", "skeleton", "spider", "creeper"};
        for (int x : new int[]{x0 + 1, x0 + 5, x0 + sx - 6, x0 + sx - 2})
            spawner(level, new BlockPos(x, y0 + 1, z0 + sz - 1), types[r.nextInt(types.length)], r);
        List<BlockPos> chests = new ArrayList<>();
        for (int x : new int[]{x0 + 8, x0 + 10, x0 + 12, x0 + 14, x0 + 16})
            chests.add(placeSingle(level, new BlockPos(x, y0 + 1, z0 + sz - 1), Direction.NORTH));
        List<ItemStack> all = new ArrayList<>();
        for (int i = 0; i < 2; i++) all.addAll(loot(m, pickLot(r), r));
        distribute(level, chests, all, r);
    }

    // ---------- Loot ----------
    static int pickLot(Random r) {
        int v = r.nextInt(100);   // Lot 1 und 2 oefter als Lot 3 und 4
        return v < 35 ? 1 : v < 70 ? 2 : v < 85 ? 3 : 4;
    }

    static void distribute(ServerLevel level, List<BlockPos> chests, List<ItemStack> items, Random r) {
        List<ChestBlockEntity> bes = new ArrayList<>();
        for (BlockPos p : chests) { BlockEntity be = level.getBlockEntity(p); if (be instanceof ChestBlockEntity c) bes.add(c); }
        if (bes.isEmpty()) return;
        List<ItemStack> shuffled = new ArrayList<>(items);
        Collections.shuffle(shuffled, r);
        for (ItemStack st : shuffled)
            for (int tries = 0; tries < 60; tries++) {
                ChestBlockEntity c = bes.get(r.nextInt(bes.size()));
                int slot = r.nextInt(27);
                if (c.getItem(slot).isEmpty()) { c.setItem(slot, st); break; }
            }
        for (ChestBlockEntity c : bes) c.setChanged();
    }

    /** Seltene Stashes: alle Kisten komplett voll. */
    static void fillRest(ServerLevel level, List<BlockPos> chests, Random r) {
        for (BlockPos p : chests) {
            BlockEntity be = level.getBlockEntity(p);
            if (!(be instanceof ChestBlockEntity c)) continue;
            for (int slot = 0; slot < 27; slot++)
                if (c.getItem(slot).isEmpty()) {
                    int k = r.nextInt(6);
                    c.setItem(slot, switch (k) {
                        case 0 -> new ItemStack(Items.ENDER_PEARL, 8 + r.nextInt(9));
                        case 1 -> new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 3 + r.nextInt(10));
                        case 2 -> new ItemStack(Items.GOLDEN_APPLE, 8 + r.nextInt(25));
                        case 3 -> new ItemStack(Items.TOTEM_OF_UNDYING);
                        case 4 -> new ItemStack(Items.EXPERIENCE_BOTTLE, 16 + r.nextInt(49));
                        default -> new ItemStack(Items.OBSIDIAN, 16 + r.nextInt(49));
                    });
                }
            c.setChanged();
        }
    }

    static ItemStack gear(DonutMod m, String id, Object... kv) {
        Map<String, Integer> e = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) e.put("minecraft:" + kv[i], (Integer) kv[i + 1]);
        return m.buildStack("minecraft:" + id, e, 1);
    }

    static void addN(List<ItemStack> out, Random r, int lo, int hi, Supplier<ItemStack> f) {
        int n = lo + r.nextInt(hi - lo + 1);
        for (int i = 0; i < n; i++) { ItemStack s = f.get(); if (!s.isEmpty()) out.add(s); }
    }

    static ItemStack potion(net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> p) {
        ItemStack s = new ItemStack(Items.POTION);
        s.set(DataComponents.POTION_CONTENTS, new PotionContents(p));
        return s;
    }

    static ItemStack rockets(Random r) {
        ItemStack s = new ItemStack(Items.FIREWORK_ROCKET, 16 + r.nextInt(17));
        s.set(DataComponents.FIREWORKS, new Fireworks(3, List.of()));
        return s;
    }

    static void stack(List<ItemStack> out, Random r, net.minecraft.world.item.Item it, int lo, int hi) {
        out.add(new ItemStack(it, lo + r.nextInt(hi - lo + 1)));
    }

    static List<ItemStack> loot(DonutMod m, int lot, Random r) {
        List<ItemStack> out = new ArrayList<>();
        boolean neth = lot >= 3;
        String mat = neth ? "netherite" : "diamond";
        // Ruestung: 3 bis 12 Teile
        int pieces = 3 + r.nextInt(10);
        for (int i = 0; i < pieces; i++) {
            String part = new String[]{"helmet", "chestplate", "leggings", "boots"}[r.nextInt(4)];
            boolean blast = (lot == 2 || lot == 4) && (part.equals("leggings") || part.equals("boots"));
            out.add(gear(m, mat + "_" + part, blast ? "blast_protection" : "protection", 4, "unbreaking", 3, "mending", 1));
        }
        switch (lot) {
            case 1 -> {
                addN(out, r, 1, 4, () -> gear(m, "mace", "wind_burst", 3, "density", 5, "unbreaking", 3, "mending", 1));
                addN(out, r, 1, 4, () -> gear(m, "diamond_spear", "sharpness", 5, "unbreaking", 3, "lunge", 3, "fire_aspect", 2, "mending", 1));
                addN(out, r, 1, 4, () -> gear(m, "diamond_sword", "sharpness", 5, "unbreaking", 3, "fire_aspect", 2, "mending", 1));
                addN(out, r, 1, 4, () -> gear(m, "diamond_pickaxe", "efficiency", 5, "silk_touch", 1, "unbreaking", 3, "mending", 1));
                addN(out, r, 1, 4, () -> gear(m, "diamond_axe", "sharpness", 5, "efficiency", 5, "unbreaking", 3, "mending", 1));
                addN(out, r, 1, 4, () -> gear(m, "shield", "unbreaking", 3, "mending", 1));
                addN(out, r, 3, 12, () -> potion(Potions.STRONG_SWIFTNESS));
                addN(out, r, 3, 12, () -> potion(Potions.STRONG_STRENGTH));
                addN(out, r, 3, 12, () -> potion(Potions.LONG_FIRE_RESISTANCE));
                stack(out, r, Items.TOTEM_OF_UNDYING, 1, 1); addN(out, r, 2, 11, () -> new ItemStack(Items.TOTEM_OF_UNDYING));
                stack(out, r, Items.GOLDEN_APPLE, 3, 12); stack(out, r, Items.ENCHANTED_GOLDEN_APPLE, 3, 12);
                stack(out, r, Items.ENDER_PEARL, 3, 12); stack(out, r, Items.COBWEB, 3, 12);
            }
            case 2 -> {
                addN(out, r, 1, 4, () -> gear(m, "diamond_sword", "sharpness", 5, "unbreaking", 3, "knockback", 1, "mending", 1));
                addN(out, r, 1, 4, () -> gear(m, "diamond_axe", "sharpness", 5, "efficiency", 5, "mending", 1, "unbreaking", 3));
                addN(out, r, 1, 4, () -> gear(m, "diamond_pickaxe", "efficiency", 5, "mending", 1, "silk_touch", 1, "unbreaking", 3));
                addN(out, r, 1, 4, () -> gear(m, "shield", "mending", 1, "unbreaking", 3));
                addN(out, r, 3, 12, () -> new ItemStack(Items.TOTEM_OF_UNDYING));
                stack(out, r, Items.GOLDEN_APPLE, 3, 12); stack(out, r, Items.ENCHANTED_GOLDEN_APPLE, 3, 12);
                stack(out, r, Items.ENDER_PEARL, 3, 12); stack(out, r, Items.END_CRYSTAL, 3, 12);
                stack(out, r, Items.OBSIDIAN, 8, 32); stack(out, r, Items.RESPAWN_ANCHOR, 3, 12);
                stack(out, r, Items.GLOWSTONE, 8, 32); stack(out, r, Items.EXPERIENCE_BOTTLE, 8, 32);
            }
            case 3 -> {
                addN(out, r, 1, 3, () -> gear(m, "elytra", "unbreaking", 3, "mending", 1));
                addN(out, r, 1, 4, () -> gear(m, "mace", "wind_burst", 3, "density", 5, "unbreaking", 3, "mending", 1));
                addN(out, r, 1, 4, () -> gear(m, "netherite_spear", "sharpness", 5, "lunge", 3, "unbreaking", 3, "fire_aspect", 2, "mending", 1));
                addN(out, r, 1, 4, () -> gear(m, "netherite_sword", "sharpness", 5, "unbreaking", 3, "fire_aspect", 2, "mending", 1));
                addN(out, r, 1, 4, () -> gear(m, "netherite_axe", "sharpness", 5, "unbreaking", 3, "efficiency", 5, "mending", 1));
                addN(out, r, 1, 4, () -> gear(m, "netherite_pickaxe", "efficiency", 5, "silk_touch", 1, "unbreaking", 3, "mending", 1));
                addN(out, r, 1, 4, () -> gear(m, "shield", "unbreaking", 3, "mending", 1));
                stack(out, r, Items.GOLDEN_APPLE, 3, 12); stack(out, r, Items.ENCHANTED_GOLDEN_APPLE, 3, 12);
                stack(out, r, Items.ENDER_PEARL, 3, 12); stack(out, r, Items.COBWEB, 3, 12);
                addN(out, r, 3, 12, () -> potion(Potions.STRONG_SWIFTNESS));
                addN(out, r, 3, 12, () -> potion(Potions.STRONG_STRENGTH));
                addN(out, r, 3, 12, () -> potion(Potions.LONG_FIRE_RESISTANCE));
                addN(out, r, 3, 12, () -> new ItemStack(Items.TOTEM_OF_UNDYING));
                out.add(rockets(r));
            }
            default -> {
                addN(out, r, 1, 4, () -> gear(m, "netherite_sword", "sharpness", 5, "unbreaking", 3, "knockback", 1, "fire_aspect", 2, "mending", 1));
                addN(out, r, 1, 4, () -> gear(m, "netherite_axe", "sharpness", 5, "unbreaking", 3, "efficiency", 5, "mending", 1));
                addN(out, r, 1, 4, () -> gear(m, "netherite_pickaxe", "efficiency", 5, "silk_touch", 1, "unbreaking", 3, "mending", 1));
                addN(out, r, 1, 4, () -> gear(m, "shield", "mending", 1, "unbreaking", 3));
                addN(out, r, 3, 12, () -> new ItemStack(Items.TOTEM_OF_UNDYING));
                stack(out, r, Items.GOLDEN_APPLE, 3, 12); stack(out, r, Items.ENCHANTED_GOLDEN_APPLE, 3, 12);
                stack(out, r, Items.ENDER_PEARL, 3, 12);
                addN(out, r, 1, 3, () -> gear(m, "elytra", "unbreaking", 3, "mending", 1));
                out.add(rockets(r));
            }
        }
        return out;
    }
}
