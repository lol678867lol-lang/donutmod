package de.donutmod.fabric;

import com.google.gson.Gson;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Ender Chest mit doppelter Groesse (54 statt 27 Plaetze), pro Welt gespeichert. Der alte Inhalt wird einmalig uebernommen. */
public final class EnderStorage {
    private EnderStorage() {}

    static final Map<UUID, SimpleContainer> BOXES = new HashMap<>();
    static final Set<String> MIGRATED = new HashSet<>();
    static final Gson GSON = new Gson();

    static final class Data {
        Map<String, List<String>> boxes = new HashMap<>();
        List<String> migrated = new ArrayList<>();
    }

    static Path file() { return DonutMod.worldDir().resolve("donutmod-enderchests.json"); }

    static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide() || player.isShiftKeyDown() || !(player instanceof ServerPlayer p)) return InteractionResult.PASS;
            if (!world.getBlockState(hit.getBlockPos()).is(Blocks.ENDER_CHEST)) return InteractionResult.PASS;
            open(p);
            return InteractionResult.SUCCESS;
        });
        ServerLifecycleEvents.SERVER_STARTED.register(s -> load());
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> save());
        ServerTickEvents.END_SERVER_TICK.register(s -> { if (s.getTickCount() % 6000 == 0) save(); });
    }

    static void open(ServerPlayer p) {
        SimpleContainer box = BOXES.computeIfAbsent(p.getUUID(), k -> new SimpleContainer(54));
        if (MIGRATED.add(p.getUUID().toString())) {
            var vanilla = p.getEnderChestInventory();
            for (int i = 0; i < vanilla.getContainerSize() && i < 27; i++) {
                ItemStack s = vanilla.getItem(i);
                if (!s.isEmpty() && box.getItem(i).isEmpty()) box.setItem(i, s.copy());
            }
            vanilla.clearContent();
            save();
        }
        p.openMenu(new SimpleMenuProvider((cid, inv, pl) ->
                new Gui.Menu(cid, inv, box, false, Set.<Integer>of(), (slot, sp) -> { }, null, sp -> save()),
                Component.literal("Ender Chest (54 Plätze)")));
    }

    static void save() {
        DonutMod m = DonutMod.INSTANCE;
        if (m == null || m.srv == null) return;
        try {
            Data d = new Data();
            for (Map.Entry<UUID, SimpleContainer> e : BOXES.entrySet()) {
                List<String> l = new ArrayList<>();
                for (int i = 0; i < 54; i++) {
                    ItemStack s = e.getValue().getItem(i);
                    String enc = s.isEmpty() ? "" : m.encodeStack(s);
                    l.add(enc == null ? "" : enc);
                }
                d.boxes.put(e.getKey().toString(), l);
            }
            d.migrated.addAll(MIGRATED);
            DonutMod.writeSafe(file(), GSON.toJson(d));
        } catch (Exception e) { System.err.println("[DonutMod] Ender Chest speichern fehlgeschlagen: " + e); }
    }

    static void load() {
        BOXES.clear();
        MIGRATED.clear();
        DonutMod m = DonutMod.INSTANCE;
        try {
            if (m == null || !Files.exists(file())) return;
            Data d = GSON.fromJson(Files.readString(file()), Data.class);
            if (d == null) return;
            MIGRATED.addAll(d.migrated);
            for (Map.Entry<String, List<String>> e : d.boxes.entrySet()) {
                SimpleContainer box = new SimpleContainer(54);
                for (int i = 0; i < 54 && i < e.getValue().size(); i++) {
                    String enc = e.getValue().get(i);
                    if (!enc.isEmpty()) { ItemStack s = m.decodeStack(enc); if (!s.isEmpty()) box.setItem(i, s); }
                }
                BOXES.put(UUID.fromString(e.getKey()), box);
            }
        } catch (Exception e) { System.err.println("[DonutMod] Ender Chest laden fehlgeschlagen: " + e); }
    }
}
