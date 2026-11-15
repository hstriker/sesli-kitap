# Sesli Kitap — Claude Code proje talimatı

Her oturumun başında bunu, sonra **`docs/DURUM.md`**'yi (nerede kalındı, bekleyen işler, Emre'nin kararları) oku.

Emre'nin kişisel EPUB/PDF seslendirme uygulaması (tek kullanıcı, Android + web). Arşiv: Google Drive'da ~2000 Türkçe kitap (750 GB'lık Drive).

## Kullanıcı hakkında

- **Emre** Türkçe konuşur, yazılımcı değil. Kısa, sade Türkçe yaz. Onun yapması gereken her şeyi **numaralı adımlarla** ver; Google Cloud işlerini bilgisayardan yapar (Opera tarayıcı; takılırsa Chrome/Edge öner).
- Ekran görüntüsü atar; yanıtı ona göre ver. Derleme/test/yayın işlerini sen yap, ona yalnızca **Release bağlantısını** ver:
  https://github.com/hstriker/sesli-kitap/releases/latest
- Arayüz metinleri **Türkçe**.

## Derleme ve hata ayıklama (önemli)

- Bu bulut ortamında Android SDK YOK (dl.google.com engelli; Emre ağ izni vermedi). APK'yı **GitHub Actions** derler (`.github/workflows/android.yml`, `assembleRelease`, sabit `keystore/debug.keystore` ile imzalı → güncellemeler üstüne kurulur).
- Her derlemenin sonucu ve günlüğü **`ci-durum`** dalına yazılır. Sonucu okumak için:
  `git fetch -q origin ci-durum && git show FETCH_HEAD:durum.txt && git show FETCH_HEAD:build-log.txt | grep -E "^e: |What went wrong" -A3`
  (`commit=` satırı push ettiğin SHA ile eşleşene kadar arka planda 20 sn'de bir bekle.) "daemon has terminated" satırı zararsız uyarıdır.
- `main`'e push → başarılıysa APK `son-surum` Release'ine konur. Üst üste push önceki derlemeyi iptal eder (cancelled = normal).
- **core/** (Android'den bağımsız Kotlin) burada test edilebilir: geçici harness (scratchpad'de `settings.gradle.kts` + `build.gradle.kts`, kotlin jvm 2.0.21, `sourceSets` `core/src`'yi gösterir; `gradle test --max-workers=1`, Maven Central 429 verirse tekrar dene). Uygulama (app/) yalnızca CI'da derlenir: import eksikleri ve Kotlin tür hatalarına dikkat.
- Web (`web/`): Node 22 + jsdom ile `metin.js` test edilebilir; Playwright Chromium ile duman testi (CDN'e erişim yok, JSZip yerel dosya).

## Mimari

- **core/** (Kotlin/JVM, testli): `ZipArchive` (kendi ZIP okuyucusu, SAF kanalları, bozuk/UTF-8 olmayan adlar), `CachedRangeSource` (Drive'dan parça parça okuma), `EpubParser` (EPUB2/3, kapak, TOC, DRM tespiti, seri — `VERSION` artınca kitaplar yeniden taranır), `ChapterTextExtractor` (dipnot/sayfa no atlama), `SentenceSplitter` (Türkçe kısaltmalar), `TurkishNormalizer` (sıra sayısı, Romen, tarih, kısaltma okunuşu), `TextDecoder` (Windows-1254, bozuk Latin-1), `TurkishText` (arama anahtarı, mojibake), `DialogDetector`, `SeriesGuess`, `PdfTextCleaner`.
- **app/** (Kotlin, Compose, Room v3, WorkManager):
  - `data/`: `Database.kt` (books, folders, positions, bookmarks, listening, shelves, shelf_books, quotes; göçler 1→2→3), `LibraryRepository` (tarama; Drive için yer tutucu kayıtlar, ağ hatasında kitabı bozuk sayma), `ScanWorker`, `EpubOpener` + `BookOpener` (EPUB/PDF ortak açıcı, `ChapterSource`), `Backup` (Drive appDataFolder, eşitleme), `Sozluk` (TDK).
  - `drive/`: `DriveAuth` (Google AuthorizationClient; kapsam parametreli: drive.readonly, cloud-platform, drive.appdata — uygulamada gizli anahtar yok), `DriveApi` (listeleme: belirli klasör → paralel gezinti, "Drive'ım" → toplu sorgu; aralıklı okuma; indirme), `DriveCache` (son 40 kitap, kitap başına kilit).
  - `player/`: `Player` (tek örnek; cümle kuyruğu, konum, kalan süre, uyku/sallama, diyalog sesi, özet, eşitleme yüklemesi), `TtsEngine` (Android TTS), `CloudTtsEngine` + `CloudSynth` (WaveNet, önbellek `files/ses`, aylık kota `cloudMonthlyLimit` 3,8 M), `PlaybackService` (MediaBrowserServiceCompat: bildirim, kilit ekranı, Android Auto), `PlayerWidget`, `PrefetchWorker`, `ExportWorker` (MP3), `Gemini`, `BookContent`.
  - `ui/`: alt menü (Ana Sayfa / Kitaplık / İstatistik / Ayarlar) `MainActivity`; `home`, `library` (klasör görünümü, raf filtresi), `stats`, `folders` (Ayarlar: klasörler, doğal ses, Gemini, yedek, hedef, tanı kaydı), `book`, `reader`, `quotes`.
  - `Kayit` (tanı kaydı, Ayarlar → Tanı kaydı → Kopyala).
- **web/**: `index.html`, `app.js` (GIS girişi, Drive, okuyucu, speechSynthesis, eşitleme), `metin.js` (core'un JS karşılığı — paragraf/cümle bölünmesi birebir aynı olmalı), `config.js` (`SK_CLIENT_ID`), `firebase.json`; `web.yml` Firebase Hosting'e yükler (`FIREBASE_SERVICE_ACCOUNT` secret).

## Google Cloud

- Proje: **Sesli Kitap** `sesli-kitap-514818`. OAuth izin ekranı "Testing" (yayınlama için gizlilik sayfası gerekiyordu); Emre test kullanıcısı. Drive API açık, `drive.readonly` izni ekli, Android istemcisi (paket `com.seslikitap`, SHA-1 `B4:1F:9C:E3:02:78:96:DB:EF:B2:B0:55:97:AA:EB:6D:95:21:EC:51`) var.
- Gerisi `docs/KURULUM-ADIMLARI.md` ve `docs/DURUM.md`'de.

## Kurallar

1. Gizli bilgi (Gemini anahtarı, Firebase JSON) repoya girmez; Gemini anahtarı telefonda saklanır.
2. Veritabanı şeması değişirse **Migration** yaz (verisi silinmesin). Taramada favori/ilerleme/süre/bitirme alanlarını koru.
3. `core/` değişince testleri çalıştır; `web/metin.js` ile uyumu bozma.
4. Commit mesajları Türkçe; sonuna oturumun ortak yazar satırlarını ekle.
