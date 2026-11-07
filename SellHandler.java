package de.donutmod.gui;

import de.donutmod.Market;
import de.donutmod.core.Stack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** /sell: Items in die oberen 5 Reihen legen. Verkauf per Button (Slot 49) oder beim Schließen. */
public class SellHandler extends GenericContainerScreenHandler {
    private static final int ITEM_SLOTS = 45, BUTTON = 49;
    private final SimpleInventory inv;
    private final ServerPlayerEntity sp;

    public static void open(ServerPlayerEntity sp) {
        sp.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (id, pinv, p) -> new SellHandler(id, pinv, new SimpleInventory(54), (ServerPlayerEntity) p),
            Text.literal("Sell")));
    }

    private SellHandler(int syncId, PlayerInventory pinv, SimpleInventory inv, ServerPlayerEntity sp) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, pinv, inv, 6);
        this.inv = inv;
        this.sp = sp;
        for (int i = ITEM_SLOTS; i < 54; i++) {
            ItemStack glass = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            glass.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
            inv.setStack(i, glass);
        }
        updateButton();
    }

    private static boolean blocked(ItemStack s) {
        return !Market.allowed(Registries.ITEM.getId(s.getItem()).toString());
    }

    private List<Stack> contents() {
        List<Stack> list = new ArrayList<>();
        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && !blocked(s)) list.add(Market.toCore(s));
        }
        return list;
    }

    private void updateButton() {
        long total = Market.sell.preview(contents());
        ItemStack b = new ItemStack(Items.EMERALD_BLOCK);
        b.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Verkaufen: " + Market.fmt(total)));
        inv.setStack(BUTTON, b);
    }

    private void sellAll() {
        List<Stack> list = contents();
        long pay = list.isEmpty() ? 0 : Market.sell.sell(sp.getUuid(), list).payout();
        boolean returned = false;
        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && blocked(s)) { sp.getInventory().offerOrDrop(s.copy()); returned = true; }
            inv.setStack(i, ItemStack.EMPTY);
        }
        if (!list.isEmpty()) sp.sendMessage(Text.literal("Verkauft für " + Market.fmt(pay)), false);
        if (returned) sp.sendMessage(Text.literal("Items anderer Mods sind ausgeschaltet (/mod on) und wurden zurückgegeben."), false);
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (slotIndex >= ITEM_SLOTS && slotIndex < 54) {          // untere Reihe gesperrt
            if (slotIndex == BUTTON && actionType == SlotActionType.PICKUP) {
                sellAll();
                sp.closeHandledScreen();
            }
            return;
        }
        super.onSlotClick(slotIndex, button, actionType, player);
        updateButton();
    }

    @Override
    public void onClosed(PlayerEntity player) {
        sellAll();                                               // Schließen verkauft ebenfalls
        super.onClosed(player);
    }
}
