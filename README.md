# DonutMod (Fabric, Minecraft 1.21.11, Singleplayer)

DIESE ZIP IST DER QUELLCODE, KEINE FERTIGE MOD. Die fertige Mod ist eine .jar-Datei, die erst durch das Bauen entsteht.

Jar bauen ohne PC-Setup (geht auch am Handy/Browser):
1. Auf github.com ein neues Repository anlegen und den ENTPACKTEN Inhalt dieser ZIP hochladen
   (inklusive des Ordners .github).
2. Oben auf "Actions" -> "build" -> "Run workflow". Nach ein paar Minuten: Run oeffnen -> "donutmod-jar" herunterladen.
3. Die .jar (aus dem Download) + Fabric Loader + Fabric API in den mods-Ordner von Minecraft 1.21.11.

Oder lokal: Java 21 + Gradle installieren, im Projektordner "gradle build" -> build/libs/donutmod-0.1.0.jar.
Vorher: Versionen in gradle.properties mit https://fabricmc.net/develop (1.21.11) abgleichen.

Befehle: /sell  /ah  /ah <preis>  /order  /order <item> <menge> <preis pro Stueck>  /bal  /market
        /mod on|off (Mod-Items verkaufbar / in /ah und Orders)  /opsettings (gleicher Schalter als GUI)
/settings: Kurze Zahlen (k/m/b/t), Geld-Anzeige ein/aus, Money Name Tags (Geld hinter dem Namen ueber dem Kopf; eigener Schalter), Position der Anzeige (gespeichert in config/donutmod.json)
Anzeige rechts in der Mitte: DonutSMP / Geld / Economy
Preise: aus Rezepten berechnet (Zutaten + 10 %), Rohstoffe nach Seltenheit; x1,25 global.
Andere Mods: deren Items und Rezepte werden automatisch eingelesen, Bots handeln mit ihnen.
Easter Egg: Schild "WerIstDas88" bei X=8888, Z=8888 (Overworld).
