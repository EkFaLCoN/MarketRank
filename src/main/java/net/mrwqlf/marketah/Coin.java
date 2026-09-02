package net.mrwqlf.marketah;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Envantere girince paraya donusen coin esyalari. */
public enum Coin {

    COIN("coin", "&6&lCOIN", "minecraft:item/coin/coin", 1000, 2000),
    COIN_PLUS("coin+", "&f&lCOIN&7&l+", "minecraft:item/coin/coin_plus", 3000, 6000),
    COIN_PLUSPLUS("coin++", "&b&lCOIN&3&l++", "minecraft:item/coin/coin_plusplus", 10000, 15000),
    BOSS_COIN("bosscoin", "&5&lBOSS COIN", "minecraft:item/coin/boss_coin", 100000, 200000);

    /** Coin esyalarini isaretleyen NBT anahtari */
    public static NamespacedKey ANAHTAR;

    public final String ad;
    public final String gorunum;
    public final String model;
    public final int min;
    public final int max;

    Coin(String ad, String gorunum, String model, int min, int max) {
        this.ad = ad;
        this.gorunum = gorunum;
        this.model = model;
        this.min = min;
        this.max = max;
    }

    public static Coin bul(String ad) {
        for (Coin c : values()) if (c.ad.equalsIgnoreCase(ad)) return c;
        return null;
    }

    public int rastgeleDeger() {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private static Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** Coin esyasini olusturur. */
    public ItemStack esya(int adet, String paraBirimi) {
        ItemStack it = new ItemStack(Material.SUNFLOWER, Math.max(1, adet));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(c(gorunum));

        List<Component> lore = new ArrayList<>();
        lore.add(c("&7Envanterine aldığında"));
        lore.add(c("&a" + String.format("%,d", min) + " - " + String.format("%,d", max)
                + " " + paraBirimi + " &7kazanırsın."));
        lore.add(Component.empty());
        lore.add(c("&8Yere at, üstüne bas."));
        meta.lore(lore);

        meta.setItemModel(NamespacedKey.fromString(model));
        meta.getPersistentDataContainer().set(ANAHTAR, PersistentDataType.STRING, ad);
        it.setItemMeta(meta);
        return it;
    }

    /** Esya bir coin ise onu doner, degilse null. */
    public static Coin coinMi(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return null;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return null;
        String ad = meta.getPersistentDataContainer().get(ANAHTAR, PersistentDataType.STRING);
        return ad == null ? null : bul(ad);
    }
}
