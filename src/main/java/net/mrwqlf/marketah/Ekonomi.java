package net.mrwqlf.marketah;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Basit, dosya tabanli para sistemi. Vault/Essentials gerektirmez. */
public final class Ekonomi {

    private static final DecimalFormat FMT =
            new DecimalFormat("#,##0.##", new DecimalFormatSymbols(Locale.forLanguageTag("tr-TR")));

    private final MarketAH plugin;
    private final File file;
    private final Map<UUID, Double> bakiye = new HashMap<>();
    private double baslangic;
    private String birim;

    public Ekonomi(MarketAH plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bakiye.yml");
        yukle();
    }

    public void yukle() {
        baslangic = plugin.getConfig().getDouble("baslangic-bakiye", 500.0);
        birim = plugin.getConfig().getString("para-birimi", "TL");
        bakiye.clear();
        if (!file.exists()) return;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (String key : cfg.getKeys(false)) {
            try {
                bakiye.put(UUID.fromString(key), cfg.getDouble(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void kaydet() {
        FileConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : bakiye.entrySet()) {
            cfg.set(e.getKey().toString(), e.getValue());
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("bakiye.yml kaydedilemedi: " + ex.getMessage());
        }
    }

    public String birim() {
        return birim;
    }

    public String bicim(double miktar) {
        return FMT.format(miktar) + " " + birim;
    }

    public double bakiye(UUID uuid) {
        return bakiye.computeIfAbsent(uuid, k -> baslangic);
    }

    public void ayarla(UUID uuid, double miktar) {
        bakiye.put(uuid, Math.max(0, yuvarla(miktar)));
    }

    public void ekle(UUID uuid, double miktar) {
        if (miktar <= 0) return;
        ayarla(uuid, bakiye(uuid) + miktar);
    }

    /** Yeterli para varsa duser ve true doner. */
    public boolean cek(UUID uuid, double miktar) {
        if (miktar <= 0) return true;
        double mevcut = bakiye(uuid);
        if (mevcut + 0.0001 < miktar) return false;
        ayarla(uuid, mevcut - miktar);
        return true;
    }

    public static double yuvarla(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
