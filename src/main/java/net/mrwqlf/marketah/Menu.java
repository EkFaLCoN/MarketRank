package net.mrwqlf.marketah;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Acik menuyu tanimak icin kullanilan holder. */
public final class Menu implements InventoryHolder {

    public enum Tip { MARKET, ILANLARIM, KUTU }

    public final Tip tip;
    public int sayfa;
    /** slot -> ilan id (MARKET/ILANLARIM) */
    public final Map<Integer, UUID> slotIlan = new HashMap<>();
    /** slot -> kutu index (KUTU) */
    public final Map<Integer, Integer> slotKutu = new HashMap<>();

    private Inventory inv;

    public Menu(Tip tip, int sayfa) {
        this.tip = tip;
        this.sayfa = sayfa;
    }

    public void setInventory(Inventory inv) {
        this.inv = inv;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inv;
    }
}
