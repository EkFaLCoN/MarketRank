package net.mrwqlf.marketah;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MarketAH extends JavaPlugin implements Listener {

    private Ekonomi ekonomi;
    private Depo depo;
    private Tabela tabela;

    private static final int SAYFA_BOYUT = 45;

    // ------------------------------------------------------------------ yasam dongusu

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ekonomi = new Ekonomi(this);
        depo = new Depo(this);
        tabela = new Tabela(this, ekonomi);
        Bukkit.getPluginManager().registerEvents(this, this);

        // Tabela guncelleme dongusu
        int hiz = Math.max(10, getConfig().getInt("tabela.guncelleme", 20));
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) tabela.goster(p);
        }, 40L, hiz);

        // Suresi dolan ilanlari kontrol et (5 saniyede bir)
        Bukkit.getScheduler().runTaskTimer(this, this::sureKontrol, 100L, 100L);
        // Otomatik kayit (5 dakikada bir)
        Bukkit.getScheduler().runTaskTimer(this, this::kaydet, 6000L, 6000L);

        getLogger().info("MarketAH aktif. Ilan sayisi: " + depo.hepsi().size());
    }

    @Override
    public void onDisable() {
        kaydet();
    }

    private void kaydet() {
        ekonomi.kaydet();
        depo.kaydet();
        if (tabela != null) tabela.kaydet();
    }

    // ------------------------------------------------------------------ yardimcilar

    private static Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s)
                .decoration(TextDecoration.ITALIC, false);
    }

    private void msj(CommandSender s, String metin) {
        s.sendMessage(c("&8[&6Market&8] &r" + metin));
    }

    /** Esyayi envantere koy, yer yoksa posta kutusuna at. */
    private void esyaVer(Player p, ItemStack item) {
        var artan = p.getInventory().addItem(item);
        if (!artan.isEmpty()) {
            for (ItemStack kalan : artan.values()) depo.kutuyaKoy(p.getUniqueId(), kalan.serializeAsBytes());
            msj(p, "&eEnvanterin dolu, esya &6/ahkutu&e icine konuldu.");
        }
    }

    private void esyaVerOfflineDahil(UUID uuid, ItemStack item) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.isOnline()) esyaVer(p, item);
        else depo.kutuyaKoy(uuid, item.serializeAsBytes());
    }

    private String isim(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return LegacyComponentSerializer.legacyAmpersand().serialize(meta.displayName());
        }
        String t = item.getType().name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private double minArtis(double mevcut) {
        double yuzde = getConfig().getDouble("acik-artirma.min-artis-yuzde", 5.0);
        double sabit = getConfig().getDouble("acik-artirma.min-artis-sabit", 10.0);
        return Ekonomi.yuvarla(Math.max(mevcut * yuzde / 100.0, sabit));
    }

    // ------------------------------------------------------------------ sure kontrolu

    private void sureKontrol() {
        long simdi = System.currentTimeMillis();
        List<Ilan> bitenler = new ArrayList<>();
        for (Ilan i : depo.hepsi().values()) if (i.suresiDoldu(simdi)) bitenler.add(i);
        if (bitenler.isEmpty()) return;

        for (Ilan i : bitenler) {
            depo.sil(i.id);
            if (i.acikArtirma && i.teklifVar()) {
                double komisyon = getConfig().getDouble("komisyon", 5.0);
                double net = Ekonomi.yuvarla(i.fiyat * (1 - komisyon / 100.0));
                ekonomi.ekle(i.satici, net);
                esyaVerOfflineDahil(i.enYuksek, i.item());

                Player kazanan = Bukkit.getPlayer(i.enYuksek);
                if (kazanan != null) msj(kazanan, "&aAcik artirmayi kazandin: &f" + isim(i.item())
                        + " &7(" + ekonomi.bicim(i.fiyat) + ")");
                Player satici = Bukkit.getPlayer(i.satici);
                if (satici != null) msj(satici, "&aEsyan satildi: &f" + isim(i.item())
                        + " &7-> " + ekonomi.bicim(net) + " (komisyon %" + komisyon + ")");
            } else {
                // teklif gelmedi veya sabit ilan suresi doldu
                esyaVerOfflineDahil(i.satici, i.item());
                Player satici = Bukkit.getPlayer(i.satici);
                if (satici != null) msj(satici, "&eIlanin suresi doldu, esyan &6/ahkutu&e icinde.");
            }
        }
        depo.kaydet();
    }

    // ------------------------------------------------------------------ GUI

    private void marketAc(Player p, int sayfa) {
        List<Ilan> liste = depo.siraliListe();
        menuAc(p, Menu.Tip.MARKET, sayfa, liste, "&8Market &7(Sayfa %d/%d)");
    }

    private void ilanlarimAc(Player p, int sayfa) {
        List<Ilan> liste = depo.oyuncununIlanlari(p.getUniqueId());
        menuAc(p, Menu.Tip.ILANLARIM, sayfa, liste, "&8Ilanlarim &7(Sayfa %d/%d)");
    }

    private void menuAc(Player p, Menu.Tip tip, int sayfa, List<Ilan> liste, String baslikSablon) {
        int maxSayfa = Math.max(1, (int) Math.ceil(liste.size() / (double) SAYFA_BOYUT));
        sayfa = Math.max(1, Math.min(sayfa, maxSayfa));

        Menu menu = new Menu(tip, sayfa);
        Inventory inv = Bukkit.createInventory(menu, 54,
                c(String.format(baslikSablon, sayfa, maxSayfa)));
        menu.setInventory(inv);

        long simdi = System.currentTimeMillis();
        int bas = (sayfa - 1) * SAYFA_BOYUT;
        for (int s = 0; s < SAYFA_BOYUT && bas + s < liste.size(); s++) {
            Ilan ilan = liste.get(bas + s);
            ItemStack goster = ilan.item().clone();
            ItemMeta meta = goster.getItemMeta();
            if (meta != null) {
                List<Component> lore = new ArrayList<>();
                if (meta.hasLore() && meta.lore() != null) lore.addAll(meta.lore());
                lore.add(c("&8&m                    "));
                lore.add(c("&7Satici: &f" + ilan.saticiAdi));
                if (ilan.acikArtirma) {
                    lore.add(c("&7Tur: &6Acik Artirma"));
                    lore.add(c("&7Guncel teklif: &a" + ekonomi.bicim(ilan.fiyat)));
                    lore.add(c("&7En yuksek: &f" + (ilan.teklifVar() ? ilan.enYuksekAdi : "yok")));
                    lore.add(c("&7Kalan sure: &f" + ilan.kalanSure(simdi)));
                    if (tip == Menu.Tip.MARKET) {
                        double sonraki = Ekonomi.yuvarla(ilan.fiyat + minArtis(ilan.fiyat));
                        lore.add(c("&eSol tik: teklif ver (&a" + ekonomi.bicim(sonraki) + "&e)"));
                    }
                } else {
                    lore.add(c("&7Tur: &bSabit Fiyat"));
                    lore.add(c("&7Fiyat: &a" + ekonomi.bicim(ilan.fiyat)));
                    lore.add(c("&7Kalan sure: &f" + ilan.kalanSure(simdi)));
                    if (tip == Menu.Tip.MARKET) lore.add(c("&eSol tik: satin al"));
                }
                if (tip == Menu.Tip.ILANLARIM) {
                    if (ilan.acikArtirma && ilan.teklifVar()) lore.add(c("&cTeklif alan ilan iptal edilemez"));
                    else lore.add(c("&cSag tik: ilani geri cek"));
                }
                meta.lore(lore);
                goster.setItemMeta(meta);
            }
            inv.setItem(s, goster);
            menu.slotIlan.put(s, ilan.id);
        }

        // alt bar
        if (sayfa > 1) inv.setItem(45, buton(Material.ARROW, "&eOnceki sayfa"));
        if (sayfa < maxSayfa) inv.setItem(53, buton(Material.ARROW, "&eSonraki sayfa"));
        inv.setItem(49, buton(Material.GOLD_INGOT, "&6Bakiyen: &a" + ekonomi.bicim(ekonomi.bakiye(p.getUniqueId()))));
        inv.setItem(47, buton(Material.WRITABLE_BOOK, tip == Menu.Tip.ILANLARIM ? "&eMarkete don" : "&eIlanlarim"));
        inv.setItem(51, buton(Material.CHEST, "&ePosta kutusu &7(/ahkutu)"));

        p.openInventory(inv);
    }

    private void kutuAc(Player p) {
        List<byte[]> liste = depo.kutu(p.getUniqueId());
        Menu menu = new Menu(Menu.Tip.KUTU, 1);
        Inventory inv = Bukkit.createInventory(menu, 54, c("&8Posta Kutusu"));
        menu.setInventory(inv);
        for (int i = 0; i < liste.size() && i < 45; i++) {
            inv.setItem(i, ItemStack.deserializeBytes(liste.get(i)));
            menu.slotKutu.put(i, i);
        }
        inv.setItem(49, buton(Material.PAPER, "&7Esyalari almak icin tikla"));
        p.openInventory(inv);
    }

    private ItemStack buton(Material mat, String ad) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(c(ad));
            it.setItemMeta(m);
        }
        return it;
    }

    // ------------------------------------------------------------------ tiklama

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (!getConfig().getBoolean("rank-sohbette", true)) return;
        Component onek = tabela.rankOnEk(e.getPlayer());
        // bayrak + irk rengi irk eklentisinden alinir, sadece onune rank eklenir
        Component isim = tabela.bayrakliIsim(e.getPlayer());
        e.renderer((source, sourceDisplayName, message, viewer) ->
                onek.append(isim)
                        .append(Component.text(": "))
                        .append(message));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(this, () -> tabela.goster(e.getPlayer()), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        tabela.kaldir(e.getPlayer());
    }

    @EventHandler
    public void onHasar(EntityDamageByEntityEvent e) {
        if (e.isCancelled()) return;
        Player vuran = null;
        if (e.getDamager() instanceof Player pl) vuran = pl;
        else if (e.getDamager() instanceof org.bukkit.entity.Projectile pr
                && pr.getShooter() instanceof Player pl2) vuran = pl2;
        if (vuran == null) return;
        tabela.hasarEkle(vuran.getUniqueId(), e.getFinalDamage());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(e.getInventory())) return;

        int slot = e.getRawSlot();

        // navigasyon
        if (slot == 45 && menu.tip != Menu.Tip.KUTU) {
            acSayfa(p, menu.tip, menu.sayfa - 1);
            return;
        }
        if (slot == 53 && menu.tip != Menu.Tip.KUTU) {
            acSayfa(p, menu.tip, menu.sayfa + 1);
            return;
        }
        if (slot == 47 && menu.tip != Menu.Tip.KUTU) {
            if (menu.tip == Menu.Tip.ILANLARIM) marketAc(p, 1);
            else ilanlarimAc(p, 1);
            return;
        }
        if (slot == 51 && menu.tip != Menu.Tip.KUTU) {
            kutuAc(p);
            return;
        }

        if (menu.tip == Menu.Tip.KUTU) {
            Integer idx = menu.slotKutu.get(slot);
            if (idx == null) return;
            List<byte[]> liste = depo.kutu(p.getUniqueId());
            if (idx >= liste.size()) return;
            ItemStack item = ItemStack.deserializeBytes(liste.get(idx.intValue()));
            if (p.getInventory().firstEmpty() == -1) {
                msj(p, "&cEnvanterinde yer yok.");
                return;
            }
            liste.remove(idx.intValue());
            p.getInventory().addItem(item);
            depo.kaydet();
            kutuAc(p);
            return;
        }

        UUID ilanId = menu.slotIlan.get(slot);
        if (ilanId == null) return;
        Ilan ilan = depo.al(ilanId);
        if (ilan == null) {
            msj(p, "&cBu ilan artik mevcut degil.");
            acSayfa(p, menu.tip, menu.sayfa);
            return;
        }

        if (menu.tip == Menu.Tip.ILANLARIM) {
            if (e.isRightClick()) geriCek(p, ilan, menu);
            return;
        }

        if (ilan.satici.equals(p.getUniqueId())) {
            msj(p, "&cKendi ilanina teklif veremezsin. &7(/ah -> Ilanlarim)");
            return;
        }

        if (ilan.acikArtirma) teklifVer(p, ilan, menu);
        else satinAl(p, ilan, menu);
    }

    private void acSayfa(Player p, Menu.Tip tip, int sayfa) {
        if (tip == Menu.Tip.ILANLARIM) ilanlarimAc(p, sayfa);
        else marketAc(p, sayfa);
    }

    private void geriCek(Player p, Ilan ilan, Menu menu) {
        if (ilan.acikArtirma && ilan.teklifVar()) {
            msj(p, "&cTeklif almis acik artirma iptal edilemez.");
            return;
        }
        depo.sil(ilan.id);
        esyaVer(p, ilan.item());
        depo.kaydet();
        msj(p, "&aIlan geri cekildi.");
        ilanlarimAc(p, menu.sayfa);
    }

    private void satinAl(Player p, Ilan ilan, Menu menu) {
        if (!ekonomi.cek(p.getUniqueId(), ilan.fiyat)) {
            msj(p, "&cYeterli paran yok. Gereken: &f" + ekonomi.bicim(ilan.fiyat));
            return;
        }
        depo.sil(ilan.id);
        double komisyon = getConfig().getDouble("komisyon", 5.0);
        double net = Ekonomi.yuvarla(ilan.fiyat * (1 - komisyon / 100.0));
        ekonomi.ekle(ilan.satici, net);
        esyaVer(p, ilan.item());
        depo.kaydet();
        ekonomi.kaydet();

        msj(p, "&aSatin aldin: &f" + isim(ilan.item()) + " &7(" + ekonomi.bicim(ilan.fiyat) + ")");
        Player satici = Bukkit.getPlayer(ilan.satici);
        if (satici != null) msj(satici, "&a" + p.getName() + " esyani satin aldi: &f" + isim(ilan.item())
                + " &7-> " + ekonomi.bicim(net));
        marketAc(p, menu.sayfa);
    }

    private void teklifVer(Player p, Ilan ilan, Menu menu) {
        double yeni = ilan.teklifVar()
                ? Ekonomi.yuvarla(ilan.fiyat + minArtis(ilan.fiyat))
                : ilan.fiyat;

        if (p.getUniqueId().equals(ilan.enYuksek)) {
            msj(p, "&eEn yuksek teklif zaten senin.");
            return;
        }
        if (!ekonomi.cek(p.getUniqueId(), yeni)) {
            msj(p, "&cYeterli paran yok. Gereken: &f" + ekonomi.bicim(yeni));
            return;
        }
        // onceki teklifi iade et
        if (ilan.teklifVar()) {
            ekonomi.ekle(ilan.enYuksek, ilan.fiyat);
            Player eski = Bukkit.getPlayer(ilan.enYuksek);
            if (eski != null) msj(eski, "&cTeklifin gecildi: &f" + isim(ilan.item())
                    + " &7(paran iade edildi)");
        }
        ilan.fiyat = yeni;
        ilan.enYuksek = p.getUniqueId();
        ilan.enYuksekAdi = p.getName();
        depo.kaydet();
        ekonomi.kaydet();

        msj(p, "&aTeklif verildi: &f" + ekonomi.bicim(yeni) + " &7(" + isim(ilan.item()) + ")");
        marketAc(p, menu.sayfa);
    }

    // ------------------------------------------------------------------ komutlar

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        String ad = cmd.getName().toLowerCase();

        if (ad.equals("ahadmin")) return ahAdmin(sender, args);

        if (ad.equals("para")) {
            if (args.length > 0) {
                OfflinePlayer hedef = Bukkit.getOfflinePlayer(args[0]);
                msj(sender, "&f" + args[0] + " &7bakiyesi: &a"
                        + ekonomi.bicim(ekonomi.bakiye(hedef.getUniqueId())));
                return true;
            }
            if (!(sender instanceof Player p)) {
                msj(sender, "&cKonsol icin: /para <oyuncu>");
                return true;
            }
            msj(p, "&7Bakiyen: &a" + ekonomi.bicim(ekonomi.bakiye(p.getUniqueId())));
            return true;
        }

        if (!(sender instanceof Player p)) {
            msj(sender, "&cBu komut sadece oyuncular icin.");
            return true;
        }

        switch (ad) {
            case "tabela" -> {
                boolean acik = tabela.degistir(p);
                msj(p, acik ? "&aTabela acildi." : "&7Tabela kapatildi.");
            }
            case "rank" -> rankBilgi(p, args);
            case "ah" -> marketAc(p, 1);
            case "ahkutu" -> kutuAc(p);
            case "sat" -> ilanKoy(p, args, false);
            case "acikartirma" -> ilanKoy(p, args, true);
            case "odeme" -> odeme(p, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void rankBilgi(Player p, String[] args) {
        if (args.length > 0) {
            OfflinePlayer hedef = Bukkit.getOfflinePlayer(args[0]);
            msj(p, "&f" + args[0] + " &7rank: &b" + tabela.rank(hedef.getUniqueId()));
            return;
        }
        UUID u = p.getUniqueId();
        msj(p, "&7Rankin: &b" + tabela.rank(u) + " &8| &7Hasar: &c"
                + Math.round(tabela.hasar(u)));
        var sonraki = tabela.sonrakiRank(u);
        if (sonraki == null) {
            msj(p, "&6En yuksek ranktasin!");
        } else {
            double eksik = sonraki.getValue() - ekonomi.bakiye(u);
            msj(p, "&7Sonraki: &b" + sonraki.getKey() + " &7- gereken: &a"
                    + ekonomi.bicim(eksik));
        }
        msj(p, "&8Tum ranklar:");
        var liste = new ArrayList<>(tabela.ranklar());
        java.util.Collections.reverse(liste);
        for (var r : liste) {
            msj(p, "  &b" + r.getKey() + " &8- &a" + ekonomi.bicim(r.getValue()));
        }
    }

    private void odeme(Player p, String[] args) {
        if (args.length < 2) {
            msj(p, "&cKullanim: /odeme <oyuncu> <miktar>");
            return;
        }
        Player hedef = Bukkit.getPlayerExact(args[0]);
        if (hedef == null) {
            msj(p, "&cOyuncu cevrimici degil.");
            return;
        }
        if (hedef.getUniqueId().equals(p.getUniqueId())) {
            msj(p, "&cKendine para gonderemezsin.");
            return;
        }
        double miktar = sayi(args[1]);
        if (miktar <= 0) {
            msj(p, "&cGecerli bir miktar gir.");
            return;
        }
        if (!ekonomi.cek(p.getUniqueId(), miktar)) {
            msj(p, "&cYeterli paran yok.");
            return;
        }
        ekonomi.ekle(hedef.getUniqueId(), miktar);
        ekonomi.kaydet();
        msj(p, "&a" + ekonomi.bicim(miktar) + " gonderildi -> &f" + hedef.getName());
        msj(hedef, "&a" + p.getName() + " sana " + ekonomi.bicim(miktar) + " gonderdi.");
    }

    private void ilanKoy(Player p, String[] args, boolean acikArtirma) {
        if (args.length < 1) {
            msj(p, acikArtirma ? "&cKullanim: /acikartirma <baslangic fiyati> [saat]"
                    : "&cKullanim: /sat <fiyat>");
            return;
        }
        ItemStack elde = p.getInventory().getItemInMainHand();
        if (elde.getType() == Material.AIR) {
            msj(p, "&cElinde esya yok.");
            return;
        }
        int max = getConfig().getInt("max-ilan", 6);
        if (depo.oyuncununIlanlari(p.getUniqueId()).size() >= max && !p.hasPermission("marketah.admin")) {
            msj(p, "&cEn fazla &f" + max + " &cilan acabilirsin.");
            return;
        }

        double fiyat = Ekonomi.yuvarla(sayi(args[0]));
        double min = getConfig().getDouble("min-fiyat", 1.0);
        double maxF = getConfig().getDouble("max-fiyat", 10000000.0);
        if (fiyat < min || fiyat > maxF) {
            msj(p, "&cFiyat &f" + ekonomi.bicim(min) + " &c- &f" + ekonomi.bicim(maxF) + " &carasinda olmali.");
            return;
        }

        long simdi = System.currentTimeMillis();
        long bitis;
        if (acikArtirma) {
            int varsayilan = getConfig().getInt("acik-artirma.varsayilan-saat", 6);
            int maxSaat = getConfig().getInt("acik-artirma.max-saat", 48);
            int saat = args.length > 1 ? (int) sayi(args[1]) : varsayilan;
            if (saat < 1 || saat > maxSaat) {
                msj(p, "&cSure 1 - " + maxSaat + " saat arasinda olmali.");
                return;
            }
            bitis = simdi + saat * 3600000L;
        } else {
            int saat = getConfig().getInt("ilan-suresi-saat", 72);
            bitis = saat > 0 ? simdi + saat * 3600000L : 0L;
        }

        ItemStack kopya = elde.clone();
        Ilan ilan = new Ilan(UUID.randomUUID(), p.getUniqueId(), p.getName(),
                kopya.serializeAsBytes(), acikArtirma, fiyat, simdi, bitis);
        depo.ekle(ilan);
        p.getInventory().setItemInMainHand(null);
        depo.kaydet();

        msj(p, "&aIlan acildi: &f" + isim(kopya) + " &7- "
                + (acikArtirma ? "acik artirma, baslangic " : "fiyat ")
                + ekonomi.bicim(fiyat));

        Bukkit.broadcast(c("&8[&6Market&8] &f" + p.getName() + " &7bir esya "
                + (acikArtirma ? "acik artirmaya koydu" : "satisa koydu") + ": &f"
                + isim(kopya) + " &7- &a" + ekonomi.bicim(fiyat) + " &8(/ah)"));
    }

    private boolean ahAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("marketah.admin")) {
            msj(sender, "&cYetkin yok.");
            return true;
        }
        if (args.length < 1) {
            msj(sender, "&7/ahadmin ver|al|ayarla <oyuncu> <miktar> &8| &7/ahadmin temizle");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "ver", "al", "ayarla" -> {
                if (args.length < 3) {
                    msj(sender, "&cKullanim: /ahadmin " + args[0] + " <oyuncu> <miktar>");
                    return true;
                }
                OfflinePlayer hedef = Bukkit.getOfflinePlayer(args[1]);
                double miktar = sayi(args[2]);
                UUID u = hedef.getUniqueId();
                switch (args[0].toLowerCase()) {
                    case "ver" -> ekonomi.ekle(u, miktar);
                    case "al" -> ekonomi.ayarla(u, ekonomi.bakiye(u) - miktar);
                    default -> ekonomi.ayarla(u, miktar);
                }
                ekonomi.kaydet();
                msj(sender, "&aTamam. &f" + args[1] + " &7yeni bakiye: &a" + ekonomi.bicim(ekonomi.bakiye(u)));
            }
            case "temizle" -> {
                int n = depo.hepsi().size();
                for (Ilan i : new ArrayList<>(depo.hepsi().values())) {
                    if (i.acikArtirma && i.teklifVar()) ekonomi.ekle(i.enYuksek, i.fiyat);
                    esyaVerOfflineDahil(i.satici, i.item());
                    depo.sil(i.id);
                }
                depo.kaydet();
                ekonomi.kaydet();
                msj(sender, "&a" + n + " ilan iptal edildi, esyalar saticilara iade edildi.");
            }
            case "reload" -> {
                ekonomi.kaydet();
                reloadConfig();
                ekonomi.yukle();
                tabela.yukle();
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    tabela.kaldir(pl);
                    tabela.goster(pl);
                }
                msj(sender, "&aConfig yeniden yuklendi.");
            }
            default -> msj(sender, "&cBilinmeyen alt komut.");
        }
        return true;
    }

    private static double sayi(String s) {
        try {
            return Double.parseDouble(s.replace(",", "."));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
