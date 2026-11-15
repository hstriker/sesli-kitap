# Sesli Kitap

EPUB kitaplarını Türkçe seslendiren Android uygulaması.

## APK'yı indirme

Her `main` güncellemesinden sonra GitHub Actions APK'yı derler ve **Releases → Son sürüm** sayfasına koyar:

https://github.com/hstriker/sesli-kitap/releases/latest

`SesliKitap.apk` dosyasını telefona indirip açın. İlk kurulumda Android "bilinmeyen kaynaklardan yükleme" izni ister.
Yeni sürümler eskisinin üstüne kurulur, kitaplığınız ve okuma konumlarınız korunur.

## Yol haritası

| Aşama | İçerik | Durum |
|---|---|---|
| 1 | Kütüphane: klasör tarama, kapak/başlık/yazar, arama, sıralama | ✅ |
| 2 | Okuyucu ve seslendirme: bölümler, cümle cümle okuma, hız/ton, kalınan yer | ✅ |
| 3 | Arka planda çalma: bildirim, kilit ekranı, Bluetooth kulaklık tuşları | ✅ |
| 4 | Cümle vurgulama, yer imleri, uyku zamanlayıcısı, dipnot/sayfa no atlama ayarı | ✅ |
| 5 | MP3 sesli kitap olarak dışa aktarma | ✅ |
| 6 | Türkçe iyileştirmeler: sayılar, tarihler, Romen rakamları, kısaltmalar | ✅ |

### Sonradan eklenenler
Google Drive, ana sayfa, istatistik ve yıllık hedef, favoriler ve raflar, seriler, alıntı defteri, kelime anlamı (TDK),
doğal ses (Google WaveNet), diyaloglarda farklı ses, kalan süre, uyku modunda sönme, PDF, Wi-Fi'da önceden hazırlama,
"Nerede kalmıştık?" (Gemini), Drive yedekleme, widget, Android Auto, bilgisayar (web) sürümü.

Google tarafındaki kurulum adımları: [docs/KURULUM-ADIMLARI.md](docs/KURULUM-ADIMLARI.md)

## Proje yapısı

- `core/`: Android'den bağımsız Kotlin kodu: ZIP/EPUB okuma, metin çıkarma, Türkçe cümle bölme. Birim testleri burada.
- `app/`: Android uygulaması (Kotlin, Jetpack Compose, Room). `player/` klasörü seslendirme motoru ve arka plan servisini içerir.
- `web/`: bilgisayar sürümü (tarayıcıda çalışır, Firebase Hosting'e `web.yml` ile yüklenir).
- `.github/workflows/android.yml`: Test + APK derleme + yayınlama. Her çalışmanın günlüğü `ci-durum` dalına yazılır.

## Sorunlu kitap bildirme

Kitaplıkta ⚠️ işaretli kitaplar açılamayan kitaplardır. "Sorunlu" filtresinden hepsini görebilirsiniz.
Kitaba dokununca hata mesajı görünür. Bu mesajı ve mümkünse dosyayı iletin.
