package de.donutmod.fabric;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Hopper schaffen ca. 12 Items pro Tick: Cooldown wird zurueckgesetzt und der Hopper-Tick mehrfach ausgefuehrt. */
public final class Hoppers {
    private Hoppers() {}
    static final int SPEED = Perf.HOPPER_SPEED;
    static final Set<HopperBlockEntity> TRACKED = Collections.newSetFromMap(new IdentityHashMap<>());

    static void register() {
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, world) -> { if (be instanceof HopperBlockEntity h) TRACKED.add(h); });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((be, world) -> { if (be instanceof HopperBlockEntity h) TRACKED.remove(h); });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (HopperBlockEntity h : new ArrayList<>(TRACKED)) {
                Level lvl = h.getLevel();
                if (lvl == null || lvl.isClientSide() || h.isRemoved()) { TRACKED.remove(h); continue; }
                BlockPos pos = h.getBlockPos();
                BlockState st = h.getBlockState();
                for (int i = 1; i < SPEED; i++) {
                    h.cooldownTime = 0;   // per Access-Widener freigegeben
                    HopperBlockEntity.pushItemsTick(lvl, pos, st, h);
                }
            }
        });
    }
}
