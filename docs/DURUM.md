# Durum (devir notu)

Son güncelleme: 16 Kasım 2026, kurulum oturumu. Son başarılı derleme: `e0688de` (Release "Son sürüm").

## Emre'nin denediği / geri bildirimleri

- 1. aşama (Drive'dan kitaplık) çalışıyor; kitaplar listeleniyor, klasör görünümü istendi ve yapıldı.
- Kitap açma ve okuma çalıştı (Dune ekran görüntüsü: cümle vurgusu, "Dr." kısaltması doğru).
- Telefon TTS (Google Konuşma Hizmetleri zaten yüklü) **çok robotik** bulundu → Google WaveNet doğal ses yazıldı (henüz kurulmadı).
- Eskiden: her açılışta tüm Drive'ı tarama (düzeltildi), internet izni eksikti (düzeltildi), aynı kitabın çift indirilmesi hatası (düzeltildi), akıcılık (release derleme ile düzeltildi; Emre doğrulamadı).

## Yazılan ama telefonda HİÇ denenmemiş özellikler (toplu deneme bekliyor)

Kalan süre, "kaçırdım", uyku sönme + sallayınca uzatma, diyaloglarda farklı ses, seriler + sıradaki kitap,
raflar, yıllık hedef/yıl özeti, alıntılar + TDK anlamı, PDF, Wi-Fi'da önceden hazırlama, MP3 dışa aktarma,
"Nerede kalmıştık?" (Gemini), Drive yedekleme + eşitleme, widget, Android Auto, web sürümü, doğal ses (WaveNet).
Veritabanı göçleri (1→2→3) gerçek veriyle denenmedi. İlk açılışta ayrıştırıcı sürümü 2 olduğu için kitaplar bir kez yeniden taranır.

## Emre'nin yapacakları (sırayla) — ayrıntı: `docs/KURULUM-ADIMLARI.md`

1. [x] Yeni APK'yı kur, uygulamanın açıldığını ve kitaplığın durduğunu doğrula (göç sorunsuz mu?)
2. [ ] **ATLANDI — A) Doğal ses**: Türkiye'de kart eklerken Google 1.500 TL tek seferlik ön ödeme istiyor; Emre ödemeyi REDDETTİ ("ücretsiz demiştin"). Ücretsiz alternatif bul (ör. telefona daha iyi TTS motoru). Eski plan:: faturalandırma (kart), Cloud Text-to-Speech API'yi aç, `cloud-platform` iznini ekle, 1 $ bütçe uyarısı → telefonda Ayarlar → Doğal ses → Google'a bağlan
3. [x] **B) Yedekleme** (izin eklendi, telefonda "Yedeklendi ✓"): `drive.appdata` iznini ekle → telefonda otomatik yedeği aç, "Şimdi yedekle"
4. [x] **C) Gemini** (Default Gemini Project'te anahtar, telefona girildi; özellik henüz denenmedi): aistudio.google.com/apikey → anahtar → Ayarlar → Yapay zekâ özeti
5. [x] **D) Android Auto** (Bilinmeyen kaynaklar açıldı; arabada denenmedi): geliştirici ayarları → Bilinmeyen kaynaklar
6. [x] **E) Web** (Firebase + Hosting açık, secret eklendi, Web istemcisi "Web client 1", Client ID `web/config.js`'te): Firebase'i projeye ekle, Hosting'i aç, hizmet hesabı JSON → GitHub secret `FIREBASE_SERVICE_ACCOUNT`, Web OAuth istemcisi (origin `https://sesli-kitap-514818.web.app`) → **Client ID'yi Claude'a ver** → Claude `web/config.js`'e yazar
7. [ ] Toplu deneme; sorunlar için Ayarlar → Tanı kaydı → Kopyala

## Kararlar

- **Ücretli/kart isteyen hiçbir adım önerme**; önce ücret/ön ödeme riskini açıkça söyle (doğal ses olayı).

- Ses: önce Android TTS, sonra Google **WaveNet** (ücretsiz kota 4 M karakter/ay; uygulama 3,8 M'de durup telefon sesine geçer). Chirp 3 HD / Gemini TTS seçilmedi (pahalı/doğrulanmadı). Emre "sınırı geçmeyiz, sorun değil" dedi.
- MP3 kaydı yalnızca doğal sesle (MP3 parçaları birleştirilir).
- OAuth izin ekranı "Testing" modunda: Google ~7 günde bir yeniden onay isteyebilir (uygulama "yeniden bağlan" der). Yayınlamak için gizlilik politikası/ana sayfa URL'si gerekir — istenirse web sitesinde basit bir sayfa yapılabilir.
- Web sürümü PDF desteklemez (yalnızca EPUB), doğal ses yerine tarayıcı sesleri (Edge: Emel/Ahmet doğal).

## Bilinen riskler / sonraki işler

- Android Auto ve widget'ın "uygulama kapalıyken oynat" yolu (ön plan servisi kısıtları) gerçek cihazda doğrulanmalı.
- PDF: pdfbox-android büyük PDF'lerde yavaş olabilir; taranmış PDF için OCR yok (istenirse ML Kit).
- Drive toplu sorgusu (`name contains 'epub'`) bazı dosyaları kaçırabilir; belirli klasör eklemek tercih edilir.
- Diyalog sesi telefon TTS'inde yalnızca birden fazla Türkçe ses varsa çalışır.
