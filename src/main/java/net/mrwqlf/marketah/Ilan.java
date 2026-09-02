package net.mrwqlf.marketah;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;
import java.util.UUID;

/** Tek bir market ilani. Hem sabit fiyatli hem acik artirma olabilir. */
public final class Ilan {

    public final UUID id;
    public final UUID satici;
    public final String saticiAdi;
    public final byte[] esya;
    public final boolean acikArtirma;
    public final long olusturma;
    public final long bitis;          // 0 = suresiz

    public double fiyat;              // sabit fiyat veya guncel teklif
    public UUID enYuksek;             // acik artirmada en yuksek teklif sahibi
    public String enYuksekAdi;

    public Ilan(UUID id, UUID satici, String saticiAdi, byte[] esya, boolean acikArtirma,
                double fiyat, long olusturma, long bitis) {
        this.id = id;
        this.satici = satici;
        this.saticiAdi = saticiAdi;
        this.esya = esya;
        this.acikArtirma = acikArtirma;
        this.fiyat = fiyat;
        this.olusturma = olusturma;
        this.bitis = bitis;
    }

    public ItemStack item() {
        return ItemStack.deserializeBytes(esya);
    }

    public String esyaBase64() {
        return Base64.getEncoder().encodeToString(esya);
    }

    public static byte[] cozB64(String s) {
        return Base64.getDecoder().decode(s);
    }

    public boolean suresiDoldu(long simdi) {
        return bitis > 0 && simdi >= bitis;
    }

    public boolean teklifVar() {
        return enYuksek != null;
    }

    /** Kalan sureyi "2s 14dk" seklinde dondurur. */
    public String kalanSure(long simdi) {
        if (bitis <= 0) return "suresiz";
        long kalan = bitis - simdi;
        if (kalan <= 0) return "bitti";
        long saat = kalan / 3600000L;
        long dk = (kalan % 3600000L) / 60000L;
        if (saat > 0) return saat + "s " + dk + "dk";
        long sn = (kalan % 60000L) / 1000L;
        return dk + "dk " + sn + "sn";
    }
}
