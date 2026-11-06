package de.donutmod;

import de.donutmod.core.Stack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.text.Text;

/** Zeigt bei jedem Item den aktuellen Wert (Order-Preis, falls höher als Sell-Preis) und den Stack-Wert. */
public class DonutClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            if (!Market.ready || stack.isEmpty()) return;
            Stack s = Market.toCore(stack);
            long unit = Market.orders.tooltipUnitValue(s);
            boolean order = unit > Market.prices.unitPrice(s);
            lines.add(Text.literal((order ? "Order: " : "Wert: ") + Market.fmt(unit)));
            if (stack.getCount() > 1)
                lines.add(Text.literal("Stack (" + stack.getCount() + "): " + Market.fmt(unit * stack.getCount())));
        });
    }
}
