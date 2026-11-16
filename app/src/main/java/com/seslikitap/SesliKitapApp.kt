package com.seslikitap

import android.app.Application
import android.content.Context
import com.seslikitap.data.AppDatabase
import com.seslikitap.data.LibraryRepository
import com.seslikitap.drive.DriveApi
import com.seslikitap.drive.DriveAuth
import com.seslikitap.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SesliKitapApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Kayit.init(this)
        try { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext) } catch (_: Throwable) {}
        container = AppContainer(this)
        if (container.prefs.autoBackup) com.seslikitap.data.BackupWorker.schedule(this)
        // Uygulama dinlerken kapandıysa Rahatsız Etmeyin açık kalmasın
        com.seslikitap.player.FocusMode.exit(this, container.prefs)
        if (container.prefs.voiceMode == "gemini") com.seslikitap.player.GeminiBankWorker.schedule(this)
        com.seslikitap.player.GentleWorker.schedule(this)
    }
}

/** Basit bağımlılık kabı: uygulama boyunca tek örnek. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase = AppDatabase.build(context)
    val prefs = Prefs(context)
    val library = LibraryRepository(context, db, appScope, prefs)
    val player by lazy { Player(appContext, db, prefs) }
    val driveAuth by lazy { DriveAuth(appContext) }
    /** Drive'daki gizli uygulama klasörüne yedekleme izni. */
    val backupAuth by lazy {
        DriveAuth(
            appContext, com.seslikitap.data.Backup.SCOPE,
            "Drive yedeklemesi için Google bağlantısı gerekiyor: Ayarlar → Yedekleme → \"Google'a bağlan\".",
        )
    }

    /** Doğal ses (Google Cloud Text-to-Speech) izni. */
    val cloudAuth by lazy {
        DriveAuth(
            appContext, DriveAuth.CLOUD_SCOPE,
            "Doğal ses için Google bağlantısı gerekiyor: Ayarlar → Doğal ses → \"Google'a bağlan\".",
        )
    }
    /** Ana sayfadan kitaplıkta belirli bir klasörü açmak için. */
    val pendingLibraryPath = kotlinx.coroutines.flow.MutableStateFlow<List<String>?>(null)
    val drive by lazy { DriveApi(driveAuth) }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    companion object {
        const val FILE = "ayarlar"
        const val PINNED_DRIVE = "pinned_drive"
    }

    var librarySort: String
        get() = sp.getString("library_sort", "TITLE") ?: "TITLE"
        set(v) = sp.edit().putString("library_sort", v).apply()

    /** Kitaplıkta klasör görünümü (Drive'daki klasör yapısı). */
    var libraryBrowse: Boolean
        get() = sp.getBoolean("library_browse", true)
        set(v) = sp.edit().putBoolean("library_browse", v).apply()

    /** Günlük dinleme hedefi (dakika). */
    var dailyGoalMin: Int
        get() = sp.getInt("daily_goal_min", 30)
        set(v) = sp.edit().putInt("daily_goal_min", v).apply()

    /** "phone": telefondaki TTS, "cloud": Google WaveNet (doğal ses). */
    var voiceMode: String
        get() = sp.getString("voice_mode", "phone") ?: "phone"
        set(v) = sp.edit().putString("voice_mode", v).apply()

    var cloudVoice: String
        get() = sp.getString("cloud_voice", "tr-TR-Wavenet-E") ?: "tr-TR-Wavenet-E"
        set(v) = sp.edit().putString("cloud_voice", v).apply()

    /** Aylık doğal ses karakter sınırı (Google'ın ücretsiz kotası 4 milyon; güvenlik payıyla). */
    var cloudMonthlyLimit: Long
        get() = sp.getLong("cloud_limit", 3_800_000L)
        set(v) = sp.edit().putLong("cloud_limit", v).apply()

    /** Bu ay Google'a gönderilen karakter sayısı. */
    @Synchronized
    fun cloudCharsThisMonth(): Long {
        val month = java.time.YearMonth.now().toString()
        return if (sp.getString("cloud_month", "") == month) sp.getLong("cloud_chars", 0L) else 0L
    }

    @Synchronized
    fun addCloudChars(n: Long) {
        val month = java.time.YearMonth.now().toString()
        val current = cloudCharsThisMonth()
        sp.edit().putString("cloud_month", month).putLong("cloud_chars", current + n).apply()
    }

    /** Ölçülen okuma hızı (hız 1,0'da saniyedeki karakter); kalan süre tahmini için. */
    var charsPerSecond: Float
        get() = sp.getFloat("chars_per_second", 14f)
        set(v) = sp.edit().putFloat("chars_per_second", v).apply()

    /** Diyaloglar ikinci sesle okunsun mu. */
    var dialogVoiceEnabled: Boolean
        get() = sp.getBoolean("dialog_voice", false)
        set(v) = sp.edit().putBoolean("dialog_voice", v).apply()

    var cloudDialogVoice: String?
        get() = sp.getString("cloud_dialog_voice", "tr-TR-Wavenet-A")
        set(v) = sp.edit().putString("cloud_dialog_voice", v).apply()

    var phoneDialogVoice: String?
        get() = sp.getString("phone_dialog_voice", null)
        set(v) = sp.edit().putString("phone_dialog_voice", v).apply()

    /** Yıllık kitap hedefi. */
    var yearlyGoal: Int
        get() = sp.getInt("yearly_goal", 12)
        set(v) = sp.edit().putInt("yearly_goal", v).apply()

    /** Doğal seste Wi-Fi'dayken sıradaki bölümleri önceden seslendir. */
    var prefetchEnabled: Boolean
        get() = sp.getBoolean("prefetch", true)
        set(v) = sp.edit().putBoolean("prefetch", v).apply()

    var prefetchChapters: Int
        get() = sp.getInt("prefetch_chapters", 3)
        set(v) = sp.edit().putInt("prefetch_chapters", v).apply()

    /** Google AI Studio (Gemini) anahtarı — "nerede kalmıştık" özeti için. Yalnızca bu telefonda saklanır. */
    var geminiKey: String?
        get() = sp.getString("gemini_key", null)
        set(v) = sp.edit().putString("gemini_key", v?.trim()).apply()

    /** "Benim zamanım": dinlerken Rahatsız Etmeyin'i aç. */
    var myTimeDnd: Boolean
        get() = sp.getBoolean("my_time_dnd", false)
        set(v) = sp.edit().putBoolean("my_time_dnd", v).apply()

    /** Rahatsız Etmeyin'i uygulama mı açtı (kapatırken yalnızca kendi açtığını kapatır). */
    var dndActiveByUs: Boolean
        get() = sp.getBoolean("dnd_by_us", false)
        set(v) = sp.edit().putBoolean("dnd_by_us", v).apply()

    /** Pil azalınca (ya da güç tasarrufunda) animasyonları ve önceden hazırlamayı kıs. */
    var batteryFriendly: Boolean
        get() = sp.getBoolean("battery_friendly", true)
        set(v) = sp.edit().putBoolean("battery_friendly", v).apply()

    /** Sakin ekranda okunan cümle gösterilsin mi. */
    var calmShowText: Boolean
        get() = sp.getBoolean("calm_show_text", true)
        set(v) = sp.edit().putBoolean("calm_show_text", v).apply()

    /** Sakin ekran görüntüsü: "yagmur", "mum", "yildiz". */
    var calmScene: String
        get() = sp.getString("calm_scene", "yagmur") ?: "yagmur"
        set(v) = sp.edit().putString("calm_scene", v).apply()

    /** Kullanılmayan günlük Gemini hakkını sıfırlanmadan önce sıradaki bölümlere harca. */
    var geminiBank: Boolean
        get() = sp.getBoolean("gemini_bank", true)
        set(v) = sp.edit().putBoolean("gemini_bank", v).apply()

    /** Son biriktirme çalışmasının özeti (Ayarlar'da gösterilir). */
    var geminiBankLast: String?
        get() = sp.getString("gemini_bank_last", null)
        set(v) = sp.edit().putString("gemini_bank_last", v).apply()

    /** Tatil (çevrimdışı) modu: dosyası telefonda tutulan ve Gemini sesi önceden hazırlanan kitaplar. */
    var offlineBooks: List<Long>
        get() = sp.getString("offline_books", null)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        set(v) = sp.edit().putString("offline_books", v.joinToString(",")).apply()

    /** Telefondaki kopyası silinmeyecek Drive dosyaları (tatil modu). */
    var pinnedDriveIds: Set<String>
        get() = sp.getStringSet(PINNED_DRIVE, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(PINNED_DRIVE, v).apply()

    /** Bölüm bitince Gemini ile kısa sesli özet. */
    var chapterSummary: Boolean
        get() = sp.getBoolean("chapter_summary", false)
        set(v) = sp.edit().putBoolean("chapter_summary", v).apply()

    /** Gemini sesinde diyaloglar için ikinci ses (null: kapalı). */
    var geminiDialogVoice: String?
        get() = sp.getString("gemini_dialog_voice", null)
        set(v) = sp.edit().putString("gemini_dialog_voice", v).apply()

    /** Okuma listesi (sıradaki kitaplar, sırasıyla). */
    var readingList: List<Long>
        get() = sp.getString("reading_list", null)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        set(v) = sp.edit().putString("reading_list", v.distinct().joinToString(",")).apply()

    /** Kitaplıkta gizlenen kitaplar (ör. çift kopyalar). */
    var hiddenBooks: Set<Long>
        get() = sp.getStringSet("hidden_books", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden_books", v.map { it.toString() }.toSet()).apply()

    /** "Yarım kalanlar" hatırlatması: kitap → bu zamana kadar sorma. */
    fun nudgeSnoozedUntil(bookId: Long): Long = sp.getLong("nudge_$bookId", 0L)
    fun snoozeNudge(bookId: Long, until: Long) = sp.edit().putLong("nudge_$bookId", until).apply()

    /** Nazik hatırlatıcı: açık mı, saat (dakika cinsinden gün içi), kaç günde bir. */
    var reminderOn: Boolean
        get() = sp.getBoolean("reminder_on", false)
        set(v) = sp.edit().putBoolean("reminder_on", v).apply()

    var reminderMinute: Int
        get() = sp.getInt("reminder_minute", 20 * 60 + 30)
        set(v) = sp.edit().putInt("reminder_minute", v).apply()

    var reminderEvery: Int
        get() = sp.getInt("reminder_every", 1)
        set(v) = sp.edit().putInt("reminder_every", v).apply()

    var lastReminderDay: String?
        get() = sp.getString("last_reminder_day", null)
        set(v) = sp.edit().putString("last_reminder_day", v).apply()

    /** Kapanış kelimesinin en son sorulduğu gün. */
    var closingAskedDay: String?
        get() = sp.getString("closing_asked_day", null)
        set(v) = sp.edit().putString("closing_asked_day", v).apply()

    /** Gemini sesi: ses adı, anlatıcı tonu, kota durumu. */
    var geminiVoice: String
        get() = sp.getString("gemini_voice", "Kore") ?: "Kore"
        set(v) = sp.edit().putString("gemini_voice", v).apply()

    var geminiNarrator: Boolean
        get() = sp.getBoolean("gemini_narrator", true)
        set(v) = sp.edit().putBoolean("gemini_narrator", v).apply()

    /** Gemini parçasının en fazla karakteri; modelin tek seferlik ses tavanına göre kendini ayarlar. */
    var geminiGroupMax: Int
        get() = sp.getInt("gemini_group_max", 6500)
        set(v) = sp.edit().putInt("gemini_group_max", v).apply()

    /** Bu zamana kadar (epoch ms) Gemini'ye istek gönderilmez; kota dolunca ayarlanır. */
    var geminiBlockedUntil: Long
        get() = sp.getLong("gemini_blocked_until", 0L)
        set(v) = sp.edit().putLong("gemini_blocked_until", v).apply()

    /** Gemini kota günü (Pasifik saatine göre) ve o gün seslendirilen karakter / istek sayısı. */
    fun geminiToday(): Pair<Long, Int> {
        val day = java.time.LocalDate.now(java.time.ZoneId.of("America/Los_Angeles")).toString()
        return if (sp.getString("gemini_day", null) == day) sp.getLong("gemini_chars", 0L) to sp.getInt("gemini_reqs", 0) else 0L to 0
    }

    private fun geminiDay() = java.time.LocalDate.now(java.time.ZoneId.of("America/Los_Angeles")).toString()

    fun addGeminiUsage(chars: Long, model: String? = null) {
        val day = geminiDay()
        val (c, r) = geminiToday()
        val e = sp.edit().putString("gemini_day", day).putLong("gemini_chars", c + chars).putInt("gemini_reqs", r + 1)
        if (model != null) e.putInt("gemini_mreqs_${day}_$model", geminiModelReqs(model) + 1)
        e.apply()
    }

    /** Bu modelle bugün yapılan istek sayısı. */
    fun geminiModelReqs(model: String): Int = sp.getInt("gemini_mreqs_${geminiDay()}_$model", 0)

    /** Modelin günlük sınırı doldu: bu zamana kadar (epoch ms) o modele istek gönderilmez. */
    fun geminiModelBlockedUntil(model: String): Long = sp.getLong("gemini_mblock_$model", 0L)
    fun setGeminiModelBlockedUntil(model: String, until: Long) = sp.edit().putLong("gemini_mblock_$model", until).apply()

    /** Modelin günlük istek sınırı (ücretsiz katman: 10; Google'ın hata yanıtından güncellenir). */
    fun geminiModelLimit(model: String): Int = sp.getInt("gemini_mlimit_$model", 10)
    fun setGeminiModelLimit(model: String, v: Int) = sp.edit().putInt("gemini_mlimit_$model", v).apply()

    /** Kullanılabilir Gemini ses modelleri (tercih sırasıyla). */
    var geminiModels: List<String>
        get() = sp.getString("gemini_models", null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = sp.edit().putString("gemini_models", v.joinToString(",")).apply()

    /** Kitapta kalınan cümlenin başı (cihazlar arası tam yer eşitleme için). */
    fun positionExcerpt(bookId: Long): String? = sp.getString("pos_excerpt_$bookId", null)
    fun setPositionExcerpt(bookId: Long, text: String) = sp.edit().putString("pos_excerpt_$bookId", text).apply()

    var autoBackup: Boolean
        get() = sp.getBoolean("auto_backup", false)
        set(v) = sp.edit().putBoolean("auto_backup", v).apply()

    var lastBackup: Long
        get() = sp.getLong("last_backup", 0L)
        set(v) = sp.edit().putLong("last_backup", v).apply()

    var speechRate: Float
        get() = sp.getFloat("speech_rate", 1f)
        set(v) = sp.edit().putFloat("speech_rate", v).apply()

    var speechPitch: Float
        get() = sp.getFloat("speech_pitch", 1f)
        set(v) = sp.edit().putFloat("speech_pitch", v).apply()

    /** Seçilen TTS uygulamasının paket adı (null: sistem varsayılanı). */
    var ttsEngine: String?
        get() = sp.getString("tts_engine", null)
        set(v) = sp.edit().putString("tts_engine", v).apply()

    var voiceName: String?
        get() = sp.getString("voice_name", null)
        set(v) = sp.edit().putString("voice_name", v).apply()

    var skipFootnotes: Boolean
        get() = sp.getBoolean("skip_footnotes", true)
        set(v) = sp.edit().putBoolean("skip_footnotes", v).apply()

    var skipPageNumbers: Boolean
        get() = sp.getBoolean("skip_page_numbers", true)
        set(v) = sp.edit().putBoolean("skip_page_numbers", v).apply()

    /** Sayıları, kısaltmaları, Romen rakamlarını Türkçe okunuşa çevir. */
    var normalizeText: Boolean
        get() = sp.getBoolean("normalize_text", true)
        set(v) = sp.edit().putBoolean("normalize_text", v).apply()

    /** Son başarılı Drive taramasının zamanı (epoch ms). */
    var lastDriveScan: Long
        get() = sp.getLong("last_drive_scan", 0L)
        set(v) = sp.edit().putLong("last_drive_scan", v).apply()

    var readerFontSize: Float
        get() = sp.getFloat("reader_font_size", 18f)
        set(v) = sp.edit().putFloat("reader_font_size", v).apply()
}

val Context.container: AppContainer get() = (applicationContext as SesliKitapApp).container
