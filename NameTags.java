package de.donutmod;

import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Money Name Tags: zeigt das Geld hinter dem Namen über dem Kopf des Spielers.
 * Läuft über ein Scoreboard-Team pro Spieler (Suffix), braucht also keinen Client-Code.
 */
final class NameTags {
    static void tick(MinecraftServer server) {
        Scoreboard sb = server.getScoreboard();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            String teamName = "dm_" + p.getUuidAsString().replace("-", "").substring(0, 12);
            Team t = sb.getTeam(teamName);
            if (!Settings.moneyTags) {
                if (t != null) sb.removeTeam(t);
                continue;
            }
            if (t == null) {
                t = sb.addTeam(teamName);
                sb.addScoreHolderToTeam(p.getNameForScoreboard(), t);
            }
            t.setSuffix(Text.literal(" " + Market.fmt(Market.eco.get(p.getUuid()))).formatted(Formatting.GREEN));
        }
    }
}
