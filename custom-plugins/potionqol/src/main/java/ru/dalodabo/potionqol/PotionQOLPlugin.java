package ru.dalodabo.potionqol;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PotionQOLPlugin extends JavaPlugin implements Listener {

    private static final int POTION_STACK_LIMIT = 32;
    private NamespacedKey mergedPotionKey;

    private static final Set<PotionType> UPGRADABLE = EnumSet.of(
            PotionType.SPEED,
            PotionType.STRENGTH,
            PotionType.JUMP,
            PotionType.INSTANT_HEAL,
            PotionType.INSTANT_DAMAGE,
            PotionType.POISON,
            PotionType.REGEN,
            PotionType.SLOWNESS,
            PotionType.TURTLE_MASTER
    );

    @Override
    public void onEnable() {
        mergedPotionKey = new NamespacedKey(this, "anvil_merged_potion");
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        Item entityItem = event.getItem();
        ItemStack stack = entityItem.getItemStack();
        if (!isStackableDrinkPotion(stack)) {
            return;
        }

        int remaining = stack.getAmount();
        Inventory inv = player.getInventory();

        // Fill existing similar potion stacks first.
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack slot = contents[i];
            if (slot == null || !isStackableDrinkPotion(slot)) {
                continue;
            }
            if (!areSimilarPotions(slot, stack)) {
                continue;
            }
            int space = POTION_STACK_LIMIT - slot.getAmount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, remaining);
            slot.setAmount(slot.getAmount() + moved);
            remaining -= moved;
        }

        // Put leftovers into empty slots.
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            if (contents[i] != null && contents[i].getType() != Material.AIR) {
                continue;
            }
            int moved = Math.min(POTION_STACK_LIMIT, remaining);
            ItemStack newStack = stack.clone();
            newStack.setAmount(moved);
            contents[i] = newStack;
            remaining -= moved;
        }

        inv.setStorageContents(contents);

        // Fully handle pickup ourselves.
        event.setCancelled(true);
        if (remaining <= 0) {
            entityItem.remove();
        } else {
            stack.setAmount(remaining);
            entityItem.setItemStack(stack);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        handlePotionStackingClick(event);
        handleAnvilResultClick(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        ItemStack oldCursor = event.getOldCursor();
        if (!isStackableDrinkPotion(oldCursor)) {
            return;
        }

        InventoryView view = event.getView();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < view.getTopInventory().getSize()) {
                continue;
            }
            ItemStack slot = view.getItem(rawSlot);
            if (slot == null || slot.getType() == Material.AIR) {
                continue;
            }
            if (!isStackableDrinkPotion(slot)) {
                continue;
            }
            if (!areSimilarPotions(slot, oldCursor)) {
                continue;
            }
            if (slot.getAmount() >= POTION_STACK_LIMIT) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory anvil = event.getInventory();
        ItemStack left = anvil.getItem(0);
        ItemStack right = anvil.getItem(1);

        ItemStack result = createMergedPotionResult(left, right);
        event.setResult(result);

        if (result != null) {
            anvil.setRepairCost(1);
            anvil.setMaximumRepairCost(39);
        }
    }

    private void handlePotionStackingClick(InventoryClickEvent event) {
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();

        // Merge cursor potion into slot potion (left/right click)
        if (isStackableDrinkPotion(cursor) && isStackableDrinkPotion(current) && areSimilarPotions(cursor, current)) {
            int space = POTION_STACK_LIMIT - current.getAmount();
            if (space <= 0) {
                event.setCancelled(true);
                return;
            }

            int toMove;
            if (event.getClick() == ClickType.RIGHT) {
                toMove = 1;
            } else {
                toMove = Math.min(space, cursor.getAmount());
            }
            toMove = Math.min(toMove, space);
            if (toMove <= 0) {
                return;
            }

            event.setCancelled(true);
            current.setAmount(current.getAmount() + toMove);
            cursor.setAmount(cursor.getAmount() - toMove);
            if (cursor.getAmount() <= 0) {
                event.setCursor(null);
            } else {
                event.setCursor(cursor);
            }
            event.setCurrentItem(current);
            return;
        }

        // Shift-click merge to player's inventory stacks
        if (!event.isShiftClick()) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (!isStackableDrinkPotion(clicked)) {
            return;
        }

        InventoryView view = event.getView();
        Inventory bottom = view.getBottomInventory();
        int sourceRawSlot = event.getRawSlot();

        ItemStack moved = clicked.clone();
        int remaining = mergePotionIntoInventory(bottom, moved, sourceRawSlot >= view.getTopInventory().getSize() ? sourceRawSlot - view.getTopInventory().getSize() : -1);

        if (remaining == moved.getAmount()) {
            return;
        }

        event.setCancelled(true);
        if (remaining <= 0) {
            event.setCurrentItem(null);
        } else {
            clicked.setAmount(remaining);
            event.setCurrentItem(clicked);
        }
    }

    private int mergePotionIntoInventory(Inventory inv, ItemStack stack, int skipSlotInThisInventory) {
        int remaining = stack.getAmount();

        ItemStack[] contents = inv.getStorageContents();

        // Fill similar stacks first.
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            if (i == skipSlotInThisInventory) {
                continue;
            }
            ItemStack slot = contents[i];
            if (slot == null || !isStackableDrinkPotion(slot)) {
                continue;
            }
            if (!areSimilarPotions(slot, stack)) {
                continue;
            }
            int space = POTION_STACK_LIMIT - slot.getAmount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, remaining);
            slot.setAmount(slot.getAmount() + moved);
            remaining -= moved;
        }

        // Fill empty slots with up to 32.
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            if (i == skipSlotInThisInventory) {
                continue;
            }
            if (contents[i] != null && contents[i].getType() != Material.AIR) {
                continue;
            }
            int moved = Math.min(POTION_STACK_LIMIT, remaining);
            ItemStack newStack = stack.clone();
            newStack.setAmount(moved);
            contents[i] = newStack;
            remaining -= moved;
        }

        inv.setStorageContents(contents);
        return remaining;
    }

    private void handleAnvilResultClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof AnvilInventory anvil)) {
            return;
        }
        if (event.getRawSlot() != 2) {
            return;
        }

        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType() == Material.AIR) {
            return;
        }
        ItemMeta meta = result.getItemMeta();
        if (meta == null) {
            return;
        }
        Byte marker = meta.getPersistentDataContainer().get(mergedPotionKey, PersistentDataType.BYTE);
        if (marker == null || marker != (byte) 1) {
            return;
        }

        // Bukkit often handles consumption itself, but in edge cases with custom result it may not.
        // Force consumption to avoid dupes.
        ItemStack left = anvil.getItem(0);
        ItemStack right = anvil.getItem(1);
        if (left != null) {
            left.setAmount(left.getAmount() - 1);
            anvil.setItem(0, left.getAmount() > 0 ? left : null);
        }
        if (right != null) {
            right.setAmount(right.getAmount() - 1);
            anvil.setItem(1, right.getAmount() > 0 ? right : null);
        }
    }

    private ItemStack createMergedPotionResult(ItemStack left, ItemStack right) {
        if (!isStackableDrinkPotion(left) || !isStackableDrinkPotion(right)) {
            return null;
        }

        if (!(left.getItemMeta() instanceof PotionMeta leftMeta) || !(right.getItemMeta() instanceof PotionMeta rightMeta)) {
            return null;
        }

        ItemStack result = tryMergeBasePotion(left, leftMeta, rightMeta);
        if (result != null) {
            return markMerged(result);
        }

        result = tryMergeCustomPotion(left, leftMeta, rightMeta);
        if (result != null) {
            return markMerged(result);
        }

        return null;
    }

    private ItemStack tryMergeBasePotion(ItemStack left, PotionMeta leftMeta, PotionMeta rightMeta) {
        if (leftMeta.hasCustomEffects() || rightMeta.hasCustomEffects()) {
            return null;
        }

        PotionData d1 = leftMeta.getBasePotionData();
        PotionData d2 = rightMeta.getBasePotionData();
        if (d1 == null || d2 == null) {
            return null;
        }
        if (d1.getType() != d2.getType()) {
            return null;
        }
        if (d1.isExtended() != d2.isExtended()) {
            return null;
        }
        if (d1.isUpgraded() != d2.isUpgraded()) {
            return null;
        }

        if (d1.isExtended() || d1.isUpgraded()) {
            return null;
        }
        if (!UPGRADABLE.contains(d1.getType())) {
            return null;
        }

        ItemStack out = left.clone();
        out.setAmount(1);
        if (!(out.getItemMeta() instanceof PotionMeta outMeta)) {
            return null;
        }
        outMeta.setBasePotionData(new PotionData(d1.getType(), false, true));
        out.setItemMeta(outMeta);
        return out;
    }

    private ItemStack tryMergeCustomPotion(ItemStack left, PotionMeta leftMeta, PotionMeta rightMeta) {
        List<PotionEffect> l = leftMeta.getCustomEffects();
        List<PotionEffect> r = rightMeta.getCustomEffects();
        if (l.size() != 1 || r.size() != 1) {
            return null;
        }

        PotionEffect e1 = l.get(0);
        PotionEffect e2 = r.get(0);
        if (!e1.getType().equals(e2.getType())) {
            return null;
        }
        if (e1.getAmplifier() != e2.getAmplifier()) {
            return null;
        }

        ItemStack out = left.clone();
        out.setAmount(1);
        if (!(out.getItemMeta() instanceof PotionMeta outMeta)) {
            return null;
        }

        outMeta.clearCustomEffects();
        PotionEffect upgraded = new PotionEffect(
                e1.getType(),
                Math.max(e1.getDuration(), e2.getDuration()),
                e1.getAmplifier() + 1,
                e1.isAmbient() || e2.isAmbient(),
                e1.hasParticles() || e2.hasParticles(),
                e1.hasIcon() || e2.hasIcon()
        );
        outMeta.addCustomEffect(upgraded, true);
        out.setItemMeta(outMeta);
        return out;
    }

    private ItemStack markMerged(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.getPersistentDataContainer().set(mergedPotionKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private boolean isStackableDrinkPotion(ItemStack stack) {
        return stack != null && stack.getType() == Material.POTION && stack.getAmount() > 0;
    }

    private boolean areSimilarPotions(ItemStack a, ItemStack b) {
        if (a == null || b == null) {
            return false;
        }
        ItemStack ac = a.clone();
        ItemStack bc = b.clone();
        ac.setAmount(1);
        bc.setAmount(1);
        return ac.isSimilar(bc);
    }
}
