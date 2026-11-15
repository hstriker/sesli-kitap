# Google Drive bağlantısı: Google Cloud kurulumu

Uygulamanın Drive'ı okuyabilmesi için Google Cloud'da bir kez kurulum yapılır. Bilgisayardan yaklaşık 10 dakika sürer.
Uygulamaya hiçbir anahtar ya da şifre girilmez: Google, uygulamayı **paket adı + imza parmak izi** ile tanır.

Gereken bilgiler:

| Alan | Değer |
|---|---|
| Paket adı | `com.seslikitap` |
| SHA-1 imza parmak izi | `B4:1F:9C:E3:02:78:96:DB:EF:B2:B0:55:97:AA:EB:6D:95:21:EC:51` |
| İzin (scope) | `https://www.googleapis.com/auth/drive.readonly` (yalnızca okuma) |

## Adımlar

1. **Proje oluştur:** https://console.cloud.google.com adresine git. Üstteki proje seçiciye tıkla → **Yeni proje**.
   Ad: `Sesli Kitap` → **Oluştur**. Oluşunca üstten bu projeyi seçili hale getir.
2. **Drive API'yi aç:** https://console.cloud.google.com/apis/library/drive.googleapis.com → **Etkinleştir**.
3. **Uygulama bilgisi (OAuth izin ekranı):** https://console.cloud.google.com/auth/overview → **Başlayın**.
   - Uygulama adı: `Sesli Kitap`, destek e-postası: kendi Gmail adresin → İleri
   - Kitle (Audience): **Harici (External)** → İleri
   - İletişim e-postası: kendi Gmail adresin → İleri → koşulları onayla → **Oluştur**
4. **Drive okuma iznini ekle:** https://console.cloud.google.com/auth/scopes → **Kapsam ekle veya kaldır**.
   Filtre kutusuna `drive.readonly` yaz → `.../auth/drive.readonly` satırını işaretle → **Güncelle** → sayfanın altında **Kaydet**.
5. **Uygulamayı yayınla:** https://console.cloud.google.com/auth/audience → **Uygulamayı yayınla** → **Onayla**.
   Böylece Drive bağlantısı 7 günde bir kopmaz. "Doğrulama gerekli" yazısı çıkabilir, önemli değil: uygulamayı sadece sen kullanacaksın.
   - Yayınlama izin vermezse: aynı sayfada **Test kullanıcıları → Kullanıcı ekle** → kendi Gmail adresini ekle.
     (Bu durumda Google, bağlantıyı yaklaşık haftada bir yeniden onaylamanı isteyebilir.)
6. **Android istemcisi oluştur:** https://console.cloud.google.com/auth/clients → **İstemci oluştur**.
   - Uygulama türü: **Android**
   - Ad: `Sesli Kitap Android`
   - Paket adı: `com.seslikitap`
   - SHA-1 sertifika parmak izi: `B4:1F:9C:E3:02:78:96:DB:EF:B2:B0:55:97:AA:EB:6D:95:21:EC:51`
   - **Oluştur**. Çıkan istemci kimliğini bir yere yazmana gerek yok.

## Telefonda

1. Uygulamada **Klasörler → Google Drive'dan klasör ekle → Google Drive'a bağlan**.
2. Google hesabını seç. "Google bu uygulamayı doğrulamadı" uyarısı çıkarsa **Gelişmiş → Sesli Kitap'a git (güvenli değil)** → **Devam**.
3. Kitap klasörüne gir → **"… klasörünü ekle"**.

## Sık görülen hatalar

| Ekrandaki mesaj | Sebep |
|---|---|
| "Google Cloud ayarı eksik ya da hatalı (kod 10)" | 6. adımdaki paket adı ya da SHA-1 yanlış girilmiş |
| "Google Drive API etkinleştirilmemiş" | 2. adım yapılmamış (açtıktan sonra birkaç dakika bekle) |
| "access_denied" / "erişim engellendi" | 5. adım: uygulama yayınlanmamış ve test kullanıcısı da eklenmemiş |
