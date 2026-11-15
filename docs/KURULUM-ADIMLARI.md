# Senin yapacağın kurulum adımları

Uygulamanın bütün özellikleri yazıldı. Bazıları Google tarafında bir kerelik ayar istiyor.
Proje: **Sesli Kitap** (`sesli-kitap-514818`). Hepsi bilgisayardan yapılır.

---

## A) Doğal ses (Google WaveNet)

1. Kart: https://console.cloud.google.com/billing/linkedaccount?project=sesli-kitap-514818 → faturalandırma hesabı bağla
2. Hizmet: https://console.cloud.google.com/apis/library/texttospeech.googleapis.com?project=sesli-kitap-514818 → **Enable**
3. İzin: https://console.cloud.google.com/auth/scopes?project=sesli-kitap-514818 → **Add or remove scopes** →
   **Manually add scopes** kutusuna `https://www.googleapis.com/auth/cloud-platform` → **Add to table** → **Update** → **Save**
4. (Önerilir) Bütçe uyarısı: https://console.cloud.google.com/billing/budgets → **Create budget** → 1 $

Telefonda: **Ayarlar → Doğal ses → Doğal sesi kullan → Google'a bağlan**.

## B) Drive yedekleme ve bilgisayarla eşitleme

1. İzin: https://console.cloud.google.com/auth/scopes?project=sesli-kitap-514818 → **Add or remove scopes** →
   **Manually add scopes** kutusuna `https://www.googleapis.com/auth/drive.appdata` → **Add to table** → **Update** → **Save**

Telefonda: **Ayarlar → Yedekleme → Her gün otomatik yedekle**'yi aç → **Şimdi yedekle**.

## C) "Nerede kalmıştık?" (Gemini)

1. https://aistudio.google.com/apikey → **Create API key** (Sesli Kitap projesini seçebilirsin) → anahtarı kopyala
2. Telefonda: **Ayarlar → Yapay zekâ özeti** → anahtarı yapıştır → **Kaydet**

## D) Android Auto

Uygulama Play Store'dan yüklenmediği için Android Auto'da bir kez izin gerekir:
1. Telefonda **Ayarlar → Bağlı cihazlar → Bağlantı tercihleri → Android Auto** (telefona göre yeri değişebilir; ayarlarda "Android Auto" ara)
2. En alttaki **Sürüm** satırına art arda 10 kez dokun → "Geliştirici ayarlarını aç" → **Tamam**
3. Sağ üstteki **⋮ → Geliştirici ayarları** → **Bilinmeyen kaynaklar**'ı işaretle
4. Arabaya bağlanınca uygulama listesinde **Sesli Kitap** görünür

## E) Bilgisayar (web) sürümü

1. **Firebase'i projeye ekle:** https://console.firebase.google.com → **Proje oluştur / Add project** →
   alttaki **"Mevcut bir Google Cloud projesine Firebase ekle"** → **Sesli Kitap**'ı seç → devam (Analytics gerekmez)
2. **Hosting'i aç:** Firebase konsolunda sol menü **Derleme (Build) → Hosting → Başlayın** → adımları **İleri** ile geç
   (komut çalıştırman gerekmez, yüklemeyi GitHub yapacak)
3. **Yükleme anahtarı:** Firebase'de ⚙️ **Proje ayarları → Hizmet hesapları → Yeni özel anahtar oluştur** → bir JSON dosyası iner
4. **GitHub'a ekle:** https://github.com/hstriker/sesli-kitap/settings/secrets/actions → **New repository secret** →
   Ad: `FIREBASE_SERVICE_ACCOUNT`, Değer: indirilen JSON dosyasının **tüm içeriği** → **Add secret**
5. **Web giriş kimliği:** https://console.cloud.google.com/auth/clients?project=sesli-kitap-514818 → **Create client** →
   - Application type: **Web application**, Name: `Sesli Kitap Web`
   - **Authorized JavaScript origins** → **Add URI**: `https://sesli-kitap-514818.web.app` ve `https://sesli-kitap-514818.firebaseapp.com`
   - **Create** → çıkan **Client ID**'yi (….apps.googleusercontent.com) bana gönder; ben `web/config.js`'e eklerim
6. B adımındaki `drive.appdata` izni web için de gereklidir.

Web adresi: **https://sesli-kitap-514818.web.app** (Edge tarayıcısıyla açman önerilir: en doğal Türkçe sesler).
