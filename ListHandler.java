package de.donutmod.gui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;

/** Schreibgeschütztes 54-Slot-GUI (für /ah und /order). Klick auf einen Slot ruft onClick(slot) auf. */
public class ListHandler extends GenericContainerScreenHandler {
    private final IntConsumer onClick;

    public static void open(ServerPlayerEntity sp, String title, ItemStack[] icons, IntConsumer onClick) {
        sp.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, pinv, p) -> {
            SimpleInventory inv = new SimpleInventory(54);
            for (int i = 0; i < Math.min(54, icons.length); i++) inv.setStack(i, icons[i]);
            return new ListHandler(id, pinv, inv, onClick);
        }, Text.literal(title)));
    }

    private ListHandler(int syncId, PlayerInventory pinv, SimpleInventory inv, IntConsumer onClick) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, pinv, inv, 6);
        this.onClick = onClick;
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType type, PlayerEntity player) {
        if (slotIndex >= 0 && slotIndex < 54) {
            if (type == SlotActionType.PICKUP) onClick.accept(slotIndex);
            return;
        }
        if (type == SlotActionType.QUICK_MOVE || type == SlotActionType.PICKUP_ALL) return;
        super.onSlotClick(slotIndex, button, type, player);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
}
