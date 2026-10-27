package de.donutmod.fabric;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** /tpa <Spieler> (zu ihm), /tpahere <Spieler> (er kommt zu dir), /tpaccept, /tpdeny. Anfragen laufen nach 60 s ab. */
public final class Tpa {
    private Tpa() {}

    static final class Req { UUID from; boolean here; long time; }

    static final Map<UUID, Req> BY_TARGET = new HashMap<>();   // Ziel-Spieler -> Anfrage
    static final long TIMEOUT_MS = 60_000;

    static void register() {
        CommandRegistrationCallback.EVENT.register((d, ctx, env) -> {
            d.register(Commands.literal("tpa").then(Commands.argument("spieler", EntityArgument.player())
                    .executes(c -> request(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "spieler"), false))));
            d.register(Commands.literal("tpahere").then(Commands.argument("spieler", EntityArgument.player())
                    .executes(c -> request(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "spieler"), true))));
            d.register(Commands.literal("tpaccept").executes(c -> accept(c.getSource().getPlayerOrException())));
            d.register(Commands.literal("tpdeny").executes(c -> deny(c.getSource().getPlayerOrException())));
        });
    }

    static int request(ServerPlayer from, ServerPlayer to, boolean here) {
        if (from.getUUID().equals(to.getUUID())) { Gui.say(from, "§cDu kannst dich nicht zu dir selbst teleportieren."); return 0; }
        Req r = new Req();
        r.from = from.getUUID(); r.here = here; r.time = System.currentTimeMillis();
        BY_TARGET.put(to.getUUID(), r);
        String name = from.getName().getString();
        Gui.say(from, "§aAnfrage an §f" + to.getName().getString() + " §agesendet (60 Sekunden gültig).");
        Gui.say(to, "§e" + name + (here ? " möchte, dass du dich zu ihm teleportierst." : " möchte sich zu dir teleportieren.")
                + " §a/tpaccept §7oder §c/tpdeny");
        DonutMod.ping(to, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.5f);
        return 1;
    }

    static Req take(ServerPlayer target) {
        Req r = BY_TARGET.remove(target.getUUID());
        if (r == null) { Gui.say(target, "§cKeine offene Teleport-Anfrage."); return null; }
        if (System.currentTimeMillis() - r.time > TIMEOUT_MS) { Gui.say(target, "§cDie Anfrage ist abgelaufen."); return null; }
        return r;
    }

    static int accept(ServerPlayer target) {
        Req r = take(target);
        if (r == null) return 0;
        ServerPlayer from = DonutMod.INSTANCE.srv.getPlayerList().getPlayer(r.from);
        if (from == null) { Gui.say(target, "§cDer Spieler ist nicht mehr online."); return 0; }
        ServerPlayer mover = r.here ? target : from;
        ServerPlayer dest = r.here ? from : target;
        Homes.tpRaw(mover, Homes.dimId(dest.level()), dest.getX(), dest.getY(), dest.getZ(), dest.getYRot(), dest.getXRot());
        Gui.say(from, "§aTeleport-Anfrage angenommen.");
        Gui.say(target, "§aTeleport-Anfrage angenommen.");
        return 1;
    }

    static int deny(ServerPlayer target) {
        Req r = take(target);
        if (r == null) return 0;
        ServerPlayer from = DonutMod.INSTANCE.srv.getPlayerList().getPlayer(r.from);
        Gui.say(target, "§eAnfrage abgelehnt.");
        if (from != null) Gui.say(from, "§c" + target.getName().getString() + " hat deine Anfrage abgelehnt.");
        return 1;
    }
}
