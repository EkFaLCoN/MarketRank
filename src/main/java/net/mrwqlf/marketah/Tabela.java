package net.mrwqlf.marketah;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

    /** oyuncuya elle verilen tag adi */
    private final Map<UUID, String> oyuncuTag = new HashMap<>();
    /** tag adi -> gorunum bicimi (& renk kodlariyla) */
    private final Map<String, String> tagTanim = new java.util.LinkedHashMap<>();
    /** tag adi -> ismin rengi (& kodu); yoksa irk rengi kullanilir */
    private final Map<String, String> tagRenk = new HashMap<>();
    private File tagDosya;


    public Tabela(MarketAH plugin, Ekonomi ekonomi) {
        this.plugin = plugin;
        this.ekonomi = ekonomi;
        this.hasarDosya = new File(plugin.getDataFolder(), "hasar.yml");
        this.tagDosya = new File(plugin.getDataFolder(), "taglar.yml");
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

        oyuncuTag.clear();
        tagTanim.clear();
        tagRenk.clear();
        if (tagDosya != null && tagDosya.exists()) {
            FileConfiguration tcfg = YamlConfiguration.loadConfiguration(tagDosya);
            ConfigurationSection ts = tcfg.getConfigurationSection("taglar");
            if (ts != null) for (String ad : ts.getKeys(false)) tagTanim.put(ad.toLowerCase(), ts.getString(ad, ad));
            ConfigurationSection rs = tcfg.getConfigurationSection("renkler");
            if (rs != null) for (String ad : rs.getKeys(false)) tagRenk.put(ad.toLowerCase(), rs.getString(ad, ""));
            ConfigurationSection os = tcfg.getConfigurationSection("oyuncular");
            if (os != null) for (String u : os.getKeys(false)) {
                try {
                    oyuncuTag.put(UUID.fromString(u), os.getString(u, "").toLowerCase());
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        if (tagTanim.isEmpty()) {
            tagTanim.put("yonetici", "&c&lYÖNETİCİ&r ");
            tagTanim.put("moderator", "&a&lMODERATÖR&r ");
            tagTanim.put("rehber", "&b&lREHBER&r ");
            kaydet();
        }

    }

    public void kaydet() {
        FileConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : hasar.entrySet()) cfg.set(e.getKey().toString(), e.getValue());
        List<String> k = new ArrayList<>();
        for (UUID u : kapali) k.add(u.toString());
        cfg.set("kapali", k);
        FileConfiguration tcfg = new YamlConfiguration();
        for (Map.Entry<String, String> e : tagTanim.entrySet()) tcfg.set("taglar." + e.getKey(), e.getValue());
        for (Map.Entry<String, String> e : tagRenk.entrySet()) tcfg.set("renkler." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, String> e : oyuncuTag.entrySet()) tcfg.set("oyuncular." + e.getKey(), e.getValue());

        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            tcfg.save(tagDosya);
            cfg.save(hasarDosya);
        } catch (IOException ex) {
            plugin.getLogger().warning("hasar.yml kaydedilemedi: " + ex.getMessage());
        }
    }

    /**
     * Rank onekini dondurur. Oyuncunun kendi isim/bayrak bilesenine
     * DOKUNMAZ - sadece onune eklenmek uzere hazirlanir.
     */
    public Component rankOnEk(Player p) {
        String tag = tagAdi(p.getUniqueId());
        if (tag == null) return Component.empty();
        return c(tagTanim.get(tag) + "&r ");
    }

    /** Tabeladaki "Rank:" satirinda gosterilecek metin. */
    public String rankMetni(Player p) {
        String tag = tagAdi(p.getUniqueId());
        if (tag == null) return "&7yok";
        // tabelada parantez gosterilmez: [ ] karakterleri temizlenir
        return tagTanim.get(tag).replace("[", "").replace("]", "").trim();
    }

    /** Oyuncunun su an vurabilecegi hasar (silah + buyu + efekt dahil). */
    public double vurusHasari(Player p) {
        double taban = 1.0;
        var attr = p.getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE);
        if (attr != null) taban = attr.getValue();

        var elde = p.getInventory().getItemInMainHand();
        int keskinlik = elde.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SHARPNESS);
        if (keskinlik > 0) taban += 1.0 + 0.5 * (keskinlik - 1);

        var guc = p.getPotionEffect(org.bukkit.potion.PotionEffectType.STRENGTH);
        if (guc != null) taban += 3.0 * (guc.getAmplifier() + 1);
        var zayiflik = p.getPotionEffect(org.bukkit.potion.PotionEffectType.WEAKNESS);
        if (zayiflik != null) taban -= 4.0 * (zayiflik.getAmplifier() + 1);

        return Math.max(0, taban);
    }

    /** Irk bayragini parantez icine alir: [⚑] */
    private Component parantezliBayrak(Component bayrak) {
        if (bayrak.equals(Component.empty())) return bayrak;
        String ac = plugin.getConfig().getString("bayrak-parantez-ac", "&8[");
        String kap = plugin.getConfig().getString("bayrak-parantez-kapa", "&8] ");
        return c(ac).append(bayrak).append(c(kap));
    }

    // ---------------------------------------------------------------- tag

    public boolean tagOlustur(String ad, String bicim) {
        return tagTanim.put(ad.toLowerCase(), bicim) == null;
    }

    /** Rutbeye sahip oyuncularin isim rengi. */
    public boolean tagRenk(String ad, String renkKodu) {
        if (!tagVar(ad)) return false;
        tagRenk.put(ad.toLowerCase(), renkKodu);
        return true;
    }

    /** Oyuncunun isim rengi: rutbe rengi varsa o, yoksa null (irk rengi kalir). */
    public NamedTextColor isimRengi(UUID uuid) {
        String tag = tagAdi(uuid);
        if (tag == null) return null;
        String kod = tagRenk.get(tag);
        if (kod == null || kod.isBlank()) return null;
        Component ornek = c(kod + "x");
        return ornek.color() == null ? null : NamedTextColor.nearestTo(ornek.color());
    }

    public boolean tagSil(String ad) {
        String k = ad.toLowerCase();
        tagRenk.remove(k);
        if (tagTanim.remove(k) == null) return false;
        oyuncuTag.entrySet().removeIf(e -> e.getValue().equals(k));
        return true;
    }

    public boolean tagVar(String ad) {
        return tagTanim.containsKey(ad.toLowerCase());
    }

    public Map<String, String> taglar() {
        return tagTanim;
    }

    public boolean tagVer(UUID uuid, String ad) {
        if (!tagVar(ad)) return false;
        oyuncuTag.put(uuid, ad.toLowerCase());
        return true;
    }

    public void tagAl(UUID uuid) {
        oyuncuTag.remove(uuid);
    }

    /** Oyuncunun tag adi, yoksa null. */
    public String tagAdi(UUID uuid) {
        String t = oyuncuTag.get(uuid);
        return (t != null && tagTanim.containsKey(t)) ? t : null;
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
        if (t == null) return rankOnEk(p).append(p.displayName());
        // bayrak (prefix) kendi rengiyle kalir; irk rengi SADECE isme uygulanir
        Component isim = Component.text(p.getName());
        NamedTextColor rutbeRengi = isimRengi(p.getUniqueId());
        if (rutbeRengi != null) isim = isim.color(rutbeRengi);
        else if (t.color() != null) isim = isim.color(t.color());
        return parantezliBayrak(t.prefix()).append(rankOnEk(p)).append(isim).append(t.suffix());
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

            Component onek = Component.empty();
            Team kaynak = ana.getEntryTeam(q.getName());   // irk eklentisinin takimi
            if (kaynak != null) {
                // once bayrak (parantezli), sonra tag, en son isim
                onek = parantezliBayrak(kaynak.prefix());
                hedef.suffix(kaynak.suffix());
                NamedTextColor rutbeRengi = isimRengi(q.getUniqueId());
                if (rutbeRengi != null) hedef.color(rutbeRengi);
                else if (kaynak.color() != null) hedef.color(NamedTextColor.nearestTo(kaynak.color()));
            }
            onek = onek.append(rankOnEk(q));
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
        satirlar[3] = "&fRütbe: &r" + rankMetni(p);
        satirlar[4] = "&r ";
        satirlar[5] = "&fVuruş Hasarı: &c" + HASAR.format(vurusHasari(p));
        satirlar[6] = "&7&m                      ";
        satirlar[7] = ip;

        for (int i = 0; i < SATIR; i++) {
            Team t = sb.getTeam("satir" + i);
            if (t != null) t.prefix(c(satirlar[i]));
        }
        isimleriSenkronla(sb);
    }
}
