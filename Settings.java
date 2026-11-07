package de.donutmod;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.*;

/** Spieler-Einstellungen (/settings), gespeichert in config/donutmod.json. */
public final class Settings {
    public static boolean shortNumbers = true;   // k/m/b/t statt voller Zahl
    public static boolean moneyHud = true;       // Geld-Anzeige (DonutSMP / Geld / Economy) ein/aus
    public static boolean moneyTags = true;      // Money Name Tags ein/aus (eigener Schalter)
    public static boolean hudRight = true;       // Anzeige rechts in der Mitte (sonst oben links)

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("donutmod.json"); }

    public static void load() {
        try {
            if (!Files.exists(file())) return;
            JsonObject o = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
            if (o.has("shortNumbers")) shortNumbers = o.get("shortNumbers").getAsBoolean();
            if (o.has("moneyHud")) moneyHud = o.get("moneyHud").getAsBoolean();
            if (o.has("moneyTags")) moneyTags = o.get("moneyTags").getAsBoolean();
            if (o.has("hudRight")) hudRight = o.get("hudRight").getAsBoolean();
        } catch (Exception ignored) {}
    }

    public static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("shortNumbers", shortNumbers);
            o.addProperty("moneyHud", moneyHud);
            o.addProperty("moneyTags", moneyTags);
            o.addProperty("hudRight", hudRight);
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception ignored) {}
    }
}
