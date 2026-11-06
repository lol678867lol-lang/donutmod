package de.donutmod;

import net.minecraft.block.Blocks;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/** Easter Egg: Ein Schild mit "WerIstDas88" bei X=8888, Z=8888 (Overworld). Erscheint, sobald ein Spieler in der Nähe ist. */
final class Easter {
    static final int X = 8888, Z = 8888;

    static void tick(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            ServerWorld w = p.getEntityWorld();
            if (w.getRegistryKey() != World.OVERWORLD) continue;
            if (p.squaredDistanceTo(X, p.getY(), Z) > 64 * 64) continue;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, X, Z);
            BlockPos pos = new BlockPos(X, y, Z);
            if (w.getBlockState(pos).isOf(Blocks.OAK_SIGN)) return;
            w.setBlockState(pos.down(), Blocks.OAK_PLANKS.getDefaultState());
            w.setBlockState(pos, Blocks.OAK_SIGN.getDefaultState());
            if (w.getBlockEntity(pos) instanceof SignBlockEntity sign) {
                sign.setText(sign.getFrontText().withMessage(0, Text.literal("WerIstDas88")), true);
                sign.markDirty();
                var st = w.getBlockState(pos);
                w.updateListeners(pos, st, st, 3);
            }
            return;
        }
    }
}
