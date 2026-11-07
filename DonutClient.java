package de.donutmod;

import de.donutmod.core.Stack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Tooltips (Wert/Stack-Wert) und Geld-Anzeige oben links: DonutSMP / Geld / Economy. */
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

        HudRenderCallback.EVENT.register((ctx, tick) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!Settings.moneyHud || !Market.ready || mc.player == null || mc.options.hudHidden) return;
            long money = Market.eco.get(mc.player.getUuid());
            var font = mc.textRenderer;
            java.util.List<Text> lines = new java.util.ArrayList<>();
            lines.add(Text.literal("DonutSMP").formatted(Formatting.GOLD, Formatting.BOLD));
            lines.add(Text.literal(Market.fmt(money)).formatted(Formatting.GREEN));
            lines.add(Text.literal("Economy").formatted(Formatting.GRAY));
            int y = Settings.hudRight ? ctx.getScaledWindowHeight() / 2 - lines.size() * 11 / 2 : 6;
            for (Text t : lines) {
                int x = Settings.hudRight ? ctx.getScaledWindowWidth() - font.getWidth(t) - 6 : 6;
                ctx.drawTextWithShadow(font, t, x, y, 0xFFFFFFFF);
                y += 11;
            }
        });
    }
}
