package de.donutmod.fabric;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/** Shard-Werkzeuge: Pickaxe/Shovel 3x3x3, Axe faellt Baeume, Hoe erntet 5x5 und pflanzt neu. */
public final class Tools {
    private Tools() {}

    static void register() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (!(world instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)) return;
            ItemStack tool = sp.getMainHandItem();
            String key = Custom.key(tool);
            if (key == null || !key.startsWith("donutmod_shard_")) return;
            switch (key) {
                case "donutmod_shard_pickaxe" -> cube(level, sp, pos, BlockTags.MINEABLE_WITH_PICKAXE, tool);
                case "donutmod_shard_shovel" -> cube(level, sp, pos, BlockTags.MINEABLE_WITH_SHOVEL, tool);
                case "donutmod_shard_axe" -> { if (state.is(BlockTags.LOGS)) tree(level, sp, pos, tool); }
                case "donutmod_shard_hoe" -> hoe(level, sp, pos, state, tool);
                default -> { }
            }
            level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0f,
                    0.8f + DonutMod.RND.nextFloat() * 0.8f);
        });
    }

    static boolean breakOne(ServerLevel level, ServerPlayer sp, BlockPos q, TagKey<Block> tag, ItemStack tool) {
        BlockState s = level.getBlockState(q);
        if (s.isAir() || !s.is(tag) || s.getDestroySpeed(level, q) < 0 || level.getBlockEntity(q) != null) return false;
        Block.dropResources(s, level, q, null, sp, tool);
        level.destroyBlock(q, false);
        return true;
    }

    static void cube(ServerLevel level, ServerPlayer sp, BlockPos c, TagKey<Block> tag, ItemStack tool) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    breakOne(level, sp, c.offset(dx, dy, dz), tag, tool);
                }
    }

    static void tree(ServerLevel level, ServerPlayer sp, BlockPos start, ItemStack tool) {
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && seen.size() < 700) {
            BlockPos c = queue.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = c.offset(dx, dy, dz);
                        if (seen.contains(n)) continue;
                        if (level.getBlockState(n).is(BlockTags.LOGS)) { seen.add(n); queue.add(n); }
                    }
        }
        seen.remove(start);
        for (BlockPos b : seen) breakOne(level, sp, b, BlockTags.LOGS, tool);
    }

    static void hoe(ServerLevel level, ServerPlayer sp, BlockPos pos, BlockState old, ItemStack tool) {
        if (!(old.getBlock() instanceof CropBlock oldCrop)) return;
        // abgebaute Pflanze direkt neu pflanzen
        if (level.getBlockState(pos.below()).is(Blocks.FARMLAND)) level.setBlock(pos, oldCrop.getStateForAge(0), 3);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos q = pos.offset(dx, 0, dz);
                BlockState s = level.getBlockState(q);
                if (s.getBlock() instanceof CropBlock cb && cb.isMaxAge(s)) {
                    Block.dropResources(s, level, q, null, sp, tool);
                    level.setBlock(q, cb.getStateForAge(0), 3);   // nur die Pflanze, die Erde bleibt
                }
            }
    }
}
