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
    private val sp = context.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

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
