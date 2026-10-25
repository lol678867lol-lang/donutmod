package de.donutmod.fabric;

import de.donutmod.core.Stack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Zeigt in jedem Tooltip (Inventar, Kisten) den Sell- und AH-Wert. Funktioniert in der Einzelspielerwelt/LAN-Host. */
public class DonutModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ItemTooltipCallback.EVENT.register((stack, ctx, flag, lines) -> {
            DonutMod m = DonutMod.INSTANCE;
            if (m == null || stack.isEmpty() || Minecraft.getInstance().getSingleplayerServer() == null) return;
            List<Stack> all = new ArrayList<>();
            DonutMod.addStacks(stack, all);
            long sell = 0, market = 0;
            for (Stack s : all) { sell += m.prices.sellStackPrice(s); market += m.prices.stackPrice(s); }
            lines.add(Component.literal("§7Sell: §a$" + DonutMod.fmt(sell)));
            lines.add(Component.literal("§7AH-Wert: §6$" + DonutMod.fmt(market)));
        });
    }
}
