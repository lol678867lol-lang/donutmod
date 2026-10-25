package de.donutmod.fabric;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Homes: bis zu 90 pro Spieler, eigenes Menue (/home, /homes), /sethome, /delhome. */
public final class Homes {
    private Homes() {}

    static final int MAX = 90;
    static final Gson GSON = new Gson();

    static final class Home {
        String name; String dim; double x, y, z; float yaw, pitch;
    }

    static final Map<UUID, Map<String, Home>> DATA = new HashMap<>();

    static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("donutmod-homes.json"); }

    static String dimId(Level l) {
        String s = l.dimension().toString();   // ResourceKey[minecraft:dimension / minecraft:overworld]
        int i = s.lastIndexOf("/ ");
        return i >= 0 ? s.substring(i + 2, s.length() - 1).trim() : "minecraft:overworld";
    }

    static Map<String, Home> of(UUID u) { return DATA.computeIfAbsent(u, k -> new LinkedHashMap<>()); }

    static String nextName(UUID u) {
        Map<String, Home> m = of(u);
        for (int i = 1; i <= MAX + 1; i++) if (!m.containsKey("home" + i)) return "home" + i;
        return "home";
    }

    static int set(ServerPlayer p, String name) {
        Map<String, Home> hs = of(p.getUUID());
        String key = name.toLowerCase(Locale.ROOT);
        if (!hs.containsKey(key) && hs.size() >= MAX) { Gui.say(p, "§cMaximal " + MAX + " Homes."); return 0; }
        Home h = new Home();
        h.name = key; h.dim = dimId(p.level());
        h.x = p.getX(); h.y = p.getY(); h.z = p.getZ(); h.yaw = p.getYRot(); h.pitch = p.getXRot();
        hs.put(key, h);
        Gui.say(p, "§aHome §f" + key + " §agesetzt (" + hs.size() + "/" + MAX + ").");
        return 1;
    }

    static void tp(ServerPlayer p, Home h) {
        MinecraftServer srv = DonutMod.INSTANCE.srv;
        String cmd = String.format(Locale.ROOT, "execute in %s run tp %s %f %f %f %f %f", h.dim, p.getUUID(), h.x, h.y, h.z, h.yaw, h.pitch);
        srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack().withSuppressedOutput(), cmd);
        Gui.say(p, "§aTeleportiert zu §f" + h.name + "§a.");
    }

    static Item icon(Home h) {
        if (h.dim.endsWith("the_nether")) return Items.NETHERRACK;
        if (h.dim.endsWith("the_end")) return Items.END_STONE;
        return Items.RED_BED;
    }

    static void openGui(ServerPlayer p) {
        UUID u = p.getUUID();
        boolean[] del = {false};
        Gui.ListGui<Home> g = new Gui.ListGui<>(DonutMod.INSTANCE, p, "Homes (max. " + MAX + ")",
                () -> new ArrayList<>(of(u).values()),
                h -> Gui.named(icon(h), "§a" + h.name,
                        "§7Welt: §f" + h.dim.replace("minecraft:", ""),
                        "§7XYZ: §f" + (int) h.x + " " + (int) h.y + " " + (int) h.z, "",
                        del[0] ? "§cKlick: löschen" : "§aKlick: teleportieren"),
                (h, pl) -> {
                    if (del[0]) { of(u).remove(h.name); Gui.say(pl, "§eHome " + h.name + " gelöscht."); }
                    else { pl.closeContainer(); tp(pl, h); }
                });
        g.extra(47, () -> del[0]
                        ? Gui.named(Items.TNT, "§cLösch-Modus: AN", "§7Klick auf ein Home löscht es", "§7Klick hier: ausschalten")
                        : Gui.named(Items.BARRIER, "§7Lösch-Modus: AUS", "§7Klick: einschalten"),
                () -> del[0] = !del[0]);
        g.extra(50, () -> Gui.named(Items.LIME_DYE, "§aHome hier setzen", "§7Name: " + nextName(u), "§7Eigener Name: /sethome <Name>"),
                () -> set(p, nextName(u)));
        g.open();
    }

    static void save() {
        try {
            Map<String, Map<String, Home>> out = new HashMap<>();
            DATA.forEach((u, m) -> out.put(u.toString(), m));
            Files.writeString(file(), GSON.toJson(out));
        } catch (Exception e) { System.err.println("[DonutMod] Homes speichern fehlgeschlagen: " + e); }
    }

    static void load() {
        try {
            if (!Files.exists(file())) return;
            Map<String, Map<String, Home>> in = GSON.fromJson(Files.readString(file()),
                    new TypeToken<Map<String, Map<String, Home>>>() {}.getType());
            DATA.clear();
            if (in != null) in.forEach((k, v) -> DATA.put(UUID.fromString(k), new LinkedHashMap<>(v)));
        } catch (Exception e) { System.err.println("[DonutMod] Homes laden fehlgeschlagen: " + e); }
    }

    static void register() {
        CommandRegistrationCallback.EVENT.register((d, ctx, env) -> {
            d.register(Commands.literal("sethome")
                    .executes(c -> set(c.getSource().getPlayerOrException(), nextName(c.getSource().getPlayerOrException().getUUID())))
                    .then(Commands.argument("name", StringArgumentType.word())
                            .executes(c -> set(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")))));
            d.register(Commands.literal("home")
                    .executes(c -> { openGui(c.getSource().getPlayerOrException()); return 1; })
                    .then(Commands.argument("name", StringArgumentType.word())
                            .suggests((ctx2, b) -> SharedSuggestionProvider.suggest(of(ctx2.getSource().getPlayerOrException().getUUID()).keySet(), b))
                            .executes(c -> {
                                ServerPlayer p = c.getSource().getPlayerOrException();
                                Home h = of(p.getUUID()).get(StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT));
                                if (h == null) { Gui.say(p, "§cKein Home mit diesem Namen."); return 0; }
                                tp(p, h);
                                return 1;
                            })));
            d.register(Commands.literal("homes").executes(c -> { openGui(c.getSource().getPlayerOrException()); return 1; }));
            d.register(Commands.literal("delhome")
                    .then(Commands.argument("name", StringArgumentType.word())
                            .suggests((ctx2, b) -> SharedSuggestionProvider.suggest(of(ctx2.getSource().getPlayerOrException().getUUID()).keySet(), b))
                            .executes(c -> {
                                ServerPlayer p = c.getSource().getPlayerOrException();
                                String n = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
                                if (of(p.getUUID()).remove(n) == null) { Gui.say(p, "§cKein Home mit diesem Namen."); return 0; }
                                Gui.say(p, "§eHome " + n + " gelöscht.");
                                return 1;
                            })));
        });
        ServerLifecycleEvents.SERVER_STARTED.register(s -> load());
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> save());
        ServerTickEvents.END_SERVER_TICK.register(s -> { if (s.getTickCount() % 6000 == 0) save(); });
    }
}
