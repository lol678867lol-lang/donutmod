package de.donutmod.fabric;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** /rtp: sofort an einen zufaelligen, sicheren Ort der Oberwelt (nicht in Wasser, Lava, Kakteen). */
public final class Rtp {
    private Rtp() {}
    static final int RADIUS = 8000;

    static void register() {
        CommandRegistrationCallback.EVENT.register((d, ctx, env) ->
                d.register(Commands.literal("rtp").executes(c -> go(c.getSource().getPlayerOrException()))));
    }

    static int go(ServerPlayer p) {
        Level lv = p.level();
        if (!(lv instanceof ServerLevel level)) return 0;
        if (!level.dimension().equals(Level.OVERWORLD)) { Gui.say(p, "§cRTP geht nur in der Oberwelt."); return 0; }
        for (int tries = 0; tries < 25; tries++) {
            int x = DonutMod.RND.nextInt(2 * RADIUS + 1) - RADIUS;
            int z = DonutMod.RND.nextInt(2 * RADIUS + 1) - RADIUS;
            level.getChunk(x >> 4, z >> 4);   // laedt den Chunk
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            BlockState below = level.getBlockState(feet.below());
            BlockState at = level.getBlockState(feet);
            if (y < 50 || !below.getFluidState().isEmpty() || !at.getFluidState().isEmpty()
                    || below.is(Blocks.CACTUS) || below.is(Blocks.MAGMA_BLOCK) || below.is(Blocks.FIRE)
                    || below.is(Blocks.CAMPFIRE) || below.is(Blocks.SWEET_BERRY_BUSH)) continue;
            Homes.tpRaw(p, "minecraft:overworld", x + 0.5, y, z + 0.5, p.getYRot(), 0f);
            Gui.say(p, "§aZufällig teleportiert nach §f" + x + " " + y + " " + z + "§a.");
            return 1;
        }
        Gui.say(p, "§cKeinen sicheren Ort gefunden, versuch es nochmal.");
        return 0;
    }
}
