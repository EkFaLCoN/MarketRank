package net.mrwqlf.marketah;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Yan tabela (scoreboard), rank hesabi ve hasar istatistigi. */
public final class Tabela {

    private static final DecimalFormat HASAR = new DecimalFormat("#,##0.#");

    private final MarketAH plugin;
    private final Ekonomi ekonomi;
    private final File hasarDosya;

    private final Map<UUID, Double> hasar = new HashMap<>();
    private final Set<UUID> kapali = new HashSet<>();
    private final Map<UUID, Scoreboard> tabelalar = new HashMap<>();
    /** oyuncu -> kisisel tabelalarda kullanilan takim adi (16 karakter siniri icin) */
    private final Map<UUID, String> takimAdi = new HashMap<>();
    private int takimSayac = 0;

    /** rank adi -> gereken para, buyukten kucuge sirali */
    private final List<Map.Entry<String, Double>> ranklar = new ArrayList<>();

    public Tabela(MarketAH plugin, Ekonomi ekonomi) {
        this.plugin = plugin;
        this.ekonomi = ekonomi;
        this.hasarDosya = new File(plugin.getDataFolder(), "hasar.yml");
        yukle();
    }

    // ---------------------------------------------------------------- veri

    public void yukle() {
        hasar.clear();
        if (hasarDosya.exists()) {
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(hasarDosya);
            for (String k : cfg.getKeys(false)) {
                if (k.equals("kapali")) continue;
                try {
                    hasar.put(UUID.fromString(k), cfg.getDouble(k));
                } catch (IllegalArgumentException ignored) {
                }
            }
            kapali.clear();
            for (String k : cfg.getStringList("kapali")) {
                try {
                    kapali.add(UUID.fromString(k));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        ranklar.clear();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("ranklar");
        if (s != null) {
            for (String ad : s.getKeys(false)) ranklar.add(Map.entry(ad, s.getDouble(ad)));
        }
        if (ranklar.isEmpty()) ranklar.add(Map.entry("Oyuncu", 0.0));
        ranklar.sort(Comparator.comparingDouble((Map.Entry<String, Double> e) -> e.getValue()).reversed());
    }

    public void kaydet() {
        FileConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : hasar.entrySet()) cfg.set(e.getKey().toString(), e.getValue());
        List<String> k = new ArrayList<>();
        for (UUID u : kapali) k.add(u.toString());
        cfg.set("kapali", k);
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(hasarDosya);
        } catch (IOException ex) {
            plugin.getLogger().warning("hasar.yml kaydedilemedi: " + ex.getMessage());
        }
    }

    public void hasarEkle(UUID uuid, double miktar) {
        if (miktar <= 0) return;
        hasar.merge(uuid, miktar, Double::sum);
    }

    public double hasar(UUID uuid) {
        return hasar.getOrDefault(uuid, 0.0);
    }

    public String rank(UUID uuid) {
        double para = ekonomi.bakiye(uuid);
        for (Map.Entry<String, Double> e : ranklar) {
            if (para >= e.getValue()) return e.getKey();
        }
        return ranklar.get(ranklar.size() - 1).getKey();
    }

    /** Bir sonraki rank ve gereken para; yoksa null. */
    public Map.Entry<String, Double> sonrakiRank(UUID uuid) {
        double para = ekonomi.bakiye(uuid);
        Map.Entry<String, Double> sonraki = null;
        for (Map.Entry<String, Double> e : ranklar) {
            if (e.getValue() > para && (sonraki == null || e.getValue() < sonraki.getValue())) sonraki = e;
        }
        return sonraki;
    }

    /**
     * Rank onekini dondurur. Oyuncunun kendi isim/bayrak bilesenine
     * DOKUNMAZ - sadece onune eklenmek uzere hazirlanir.
     */
    public Component rankOnEk(Player p) {
        String fmt = plugin.getConfig().getString("rank-onek", "&8[&b{rank}&8] &r");
        return c(fmt.replace("{rank}", rank(p.getUniqueId())));
    }

    public List<Map.Entry<String, Double>> ranklar() {
        return ranklar;
    }

    // ---------------------------------------------------------------- tabela

    public boolean acikMi(UUID uuid) {
        return !kapali.contains(uuid);
    }

    /** Tabelayi ac/kapa, yeni durumu doner. */
    public boolean degistir(Player p) {
        UUID u = p.getUniqueId();
        if (kapali.remove(u)) {
            goster(p);
            kaydet();
            return true;
        }
        kapali.add(u);
        kaldir(p);
        kaydet();
        return false;
    }

    public void kaldir(Player p) {
        tabelalar.remove(p.getUniqueId());
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    public void goster(Player p) {
        if (!plugin.getConfig().getBoolean("tabela.aktif", true)) return;
        if (!acikMi(p.getUniqueId())) return;

        Scoreboard sb = tabelalar.get(p.getUniqueId());
        if (sb == null || p.getScoreboard() != sb) {
            sb = kur(p);
            tabelalar.put(p.getUniqueId(), sb);
            p.setScoreboard(sb);
        }
        guncelle(p, sb);
    }

    /** Irk eklentisinin bayragi + oyuncu ismi (ana scoreboard takimindan). */
    public Component bayrakliIsim(Player p) {
        Team t = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(p.getName());
        if (t == null) return p.displayName();
        // bayrak (prefix) kendi rengiyle kalir; irk rengi SADECE isme uygulanir
        Component isim = Component.text(p.getName());
        if (t.color() != null) isim = isim.color(t.color());
        return t.prefix().append(isim).append(t.suffix());
    }

    private Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** Satir sayisi: bos, Oyuncu, Para, Rank, bos, Hasar, bos, IP = 8 */
    private static final int SATIR = 8;

    private Scoreboard kur(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("marketah", Criteria.DUMMY,
                c(plugin.getConfig().getString("tabela.sunucu-adi", "&6&lSUNUCU")));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        for (int i = 0; i < SATIR; i++) {
            String entry = girdi(i);
            Team t = sb.registerNewTeam("satir" + i);
            t.addEntry(entry);
            obj.getScore(entry).setScore(SATIR - i);
        }
        return sb;
    }

    /** Her satir icin gorunmez benzersiz girdi. */
    private static String girdi(int i) {
        ChatColor[] renkler = ChatColor.values();
        return renkler[i % renkler.length].toString() + ChatColor.RESET;
    }

    private String takimAdi(Player q) {
        return takimAdi.computeIfAbsent(q.getUniqueId(), k -> "mah" + (takimSayac++ % 100000));
    }

    /**
     * Kisisel tabelaya isim satirlarini kurar:
     *   [Rank] + (irk eklentisinin bayragi) + isim
     * Ana scoreboard'a DOKUNULMAZ; irk eklentisinin bayragi ve rengi
     * oradan oldugu gibi kopyalanir, sadece onune rank eklenir.
     */
    private void isimleriSenkronla(Scoreboard sb) {
        if (!plugin.getConfig().getBoolean("rank-isim-oneki", true)) return;
        Scoreboard ana = Bukkit.getScoreboardManager().getMainScoreboard();

        for (Player q : Bukkit.getOnlinePlayers()) {
            String tad = takimAdi(q);
            Team hedef = sb.getTeam(tad);
            if (hedef == null) hedef = sb.registerNewTeam(tad);

            Component onek = rankOnEk(q);
            Team kaynak = ana.getEntryTeam(q.getName());   // irk eklentisinin takimi
            if (kaynak != null) {
                // bayrak + irk rengi aynen korunur, rank sadece onune eklenir
                onek = onek.append(kaynak.prefix());
                hedef.suffix(kaynak.suffix());
                if (kaynak.color() != null) hedef.color(kaynak.color());
            }
            hedef.prefix(onek);
            if (!hedef.hasEntry(q.getName())) hedef.addEntry(q.getName());
        }

        // cikan oyuncularin takimlarini temizle
        for (Team t : new ArrayList<>(sb.getTeams())) {
            if (!t.getName().startsWith("mah")) continue;
            boolean var = false;
            for (String e : t.getEntries()) {
                if (Bukkit.getPlayerExact(e) != null) {
                    var = true;
                    break;
                }
            }
            if (!var) t.unregister();
        }
    }

    private void guncelle(Player p, Scoreboard sb) {
        UUID u = p.getUniqueId();
        String ip = plugin.getConfig().getString("tabela.sunucu-ip", "&fsunucu.ip");

        String displayAd = LegacyComponentSerializer.legacyAmpersand().serialize(bayrakliIsim(p));

        String[] satirlar = new String[SATIR];
        satirlar[0] = "&7&m                      ";
        satirlar[1] = "&fOyuncu: &r" + displayAd;
        satirlar[2] = "&fPara: &a" + ekonomi.bicim(ekonomi.bakiye(u));
        satirlar[3] = "&fRank: &b" + rank(u);
        satirlar[4] = "&r ";
        satirlar[5] = "&fHasar: &c" + HASAR.format(hasar(u));
        satirlar[6] = "&7&m                      ";
        satirlar[7] = ip;

        for (int i = 0; i < SATIR; i++) {
            Team t = sb.getTeam("satir" + i);
            if (t != null) t.prefix(c(satirlar[i]));
        }
        isimleriSenkronla(sb);
    }
}
