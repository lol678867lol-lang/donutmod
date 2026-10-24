# DonutMod (Fabric, Minecraft 1.21.11)

Bauen: `gradle wrapper` (einmalig) -> `./gradlew build` -> `build/libs/donutmod-1.0.0.jar` in den mods-Ordner (+ Fabric API).
Versionen in gradle.properties bei https://fabricmc.net/develop pruefen (Loom, Loader, Fabric API).

Commands: /sell [all|preview], /bal, /meta, /orders, /ah, /ah sell <preis>, /ah buy <id>
Scoreboard: rechts mittig, zeigt dein eigenes Geld (jede Sekunde aktualisiert).
Speichern: Kontostaende in config/donutmod-balances.json (alle 5 Min + beim Beenden).
Core (de.donutmod.core) ist unveraendert aus donutmod-core.zip, nur Demo.java entfernt.

## Ohne Installation bauen (GitHub)
1. Auf github.com ein neues Repository erstellen und alle Dateien dieses Ordners hochladen (inkl. .github).
2. Reiter "Actions" -> "build" laeuft automatisch (oder "Run workflow").
3. Fertigen Lauf oeffnen -> unten bei "Artifacts" -> "donutmod-jar" herunterladen -> entpacken.
4. donutmod-1.0.0.jar + Fabric API in den mods-Ordner (Minecraft 1.21.11, Fabric Loader).
