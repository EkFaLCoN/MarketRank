package net.mrwqlf.marketah;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ilanlarin ve posta kutusunun disk uzerinde tutulmasi. */
public final class Depo {

    private final MarketAH plugin;
    private final File ilanDosya;
    private final File kutuDosya;

    private final Map<UUID, Ilan> ilanlar = new LinkedHashMap<>();
    private final Map<UUID, List<byte[]>> kutu = new HashMap<>();

    public Depo(MarketAH plugin) {
        this.plugin = plugin;
        this.ilanDosya = new File(plugin.getDataFolder(), "ilanlar.yml");
        this.kutuDosya = new File(plugin.getDataFolder(), "kutu.yml");
        yukle();
    }

    // ---------- ilanlar ----------

    public Map<UUID, Ilan> hepsi() {
        return ilanlar;
    }

    public List<Ilan> siraliListe() {
        List<Ilan> l = new ArrayList<>(ilanlar.values());
        l.sort(Comparator.comparingLong((Ilan i) -> i.acikArtirma ? 0 : 1)
                .thenComparingLong(i -> i.bitis > 0 ? i.bitis : Long.MAX_VALUE));
        return l;
    }

    public List<Ilan> oyuncununIlanlari(UUID uuid) {
        List<Ilan> l = new ArrayList<>();
        for (Ilan i : ilanlar.values()) if (i.satici.equals(uuid)) l.add(i);
        return l;
    }

    public void ekle(Ilan ilan) {
        ilanlar.put(ilan.id, ilan);
    }

    public Ilan al(UUID id) {
        return ilanlar.get(id);
    }

    public void sil(UUID id) {
        ilanlar.remove(id);
    }

    // ---------- posta kutusu ----------

    public void kutuyaKoy(UUID uuid, byte[] esya) {
        kutu.computeIfAbsent(uuid, k -> new ArrayList<>()).add(esya);
    }

    public List<byte[]> kutu(UUID uuid) {
        return kutu.computeIfAbsent(uuid, k -> new ArrayList<>());
    }

    // ---------- disk ----------

    public void yukle() {
        ilanlar.clear();
        kutu.clear();

        if (ilanDosya.exists()) {
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(ilanDosya);
            for (String key : cfg.getKeys(false)) {
                ConfigurationSection s = cfg.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    Ilan ilan = new Ilan(
                            UUID.fromString(key),
                            UUID.fromString(s.getString("satici", "")),
                            s.getString("satici-adi", "?"),
                            Ilan.cozB64(s.getString("esya", "")),
                            s.getBoolean("acik-artirma", false),
                            s.getDouble("fiyat", 1.0),
                            s.getLong("olusturma", System.currentTimeMillis()),
                            s.getLong("bitis", 0L));
                    String ey = s.getString("en-yuksek", null);
                    if (ey != null && !ey.isEmpty()) {
                        ilan.enYuksek = UUID.fromString(ey);
                        ilan.enYuksekAdi = s.getString("en-yuksek-adi", "?");
                    }
                    ilanlar.put(ilan.id, ilan);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Bozuk ilan atlandi: " + key + " (" + ex.getMessage() + ")");
                }
            }
        }

        if (kutuDosya.exists()) {
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(kutuDosya);
            for (String key : cfg.getKeys(false)) {
                try {
                    List<byte[]> l = new ArrayList<>();
                    for (String b64 : cfg.getStringList(key)) l.add(Ilan.cozB64(b64));
                    kutu.put(UUID.fromString(key), l);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void kaydet() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();

        FileConfiguration cfg = new YamlConfiguration();
        for (Ilan i : ilanlar.values()) {
            String k = i.id.toString();
            cfg.set(k + ".satici", i.satici.toString());
            cfg.set(k + ".satici-adi", i.saticiAdi);
            cfg.set(k + ".esya", i.esyaBase64());
            cfg.set(k + ".acik-artirma", i.acikArtirma);
            cfg.set(k + ".fiyat", i.fiyat);
            cfg.set(k + ".olusturma", i.olusturma);
            cfg.set(k + ".bitis", i.bitis);
            if (i.enYuksek != null) {
                cfg.set(k + ".en-yuksek", i.enYuksek.toString());
                cfg.set(k + ".en-yuksek-adi", i.enYuksekAdi);
            }
        }

        FileConfiguration kcfg = new YamlConfiguration();
        for (Map.Entry<UUID, List<byte[]>> e : kutu.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            List<String> l = new ArrayList<>();
            for (byte[] b : e.getValue()) l.add(java.util.Base64.getEncoder().encodeToString(b));
            kcfg.set(e.getKey().toString(), l);
        }

        try {
            cfg.save(ilanDosya);
            kcfg.save(kutuDosya);
        } catch (IOException ex) {
            plugin.getLogger().warning("Market verisi kaydedilemedi: " + ex.getMessage());
        }
    }
}
