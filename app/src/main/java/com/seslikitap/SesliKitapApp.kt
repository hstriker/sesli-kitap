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
