package com.seslikitap

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tanı kaydı: önemli olaylar (Drive istekleri, tarama, kitap açma) dosyaya yazılır.
 * Kullanıcı Klasörler → Tanı kaydı ekranından kopyalayıp paylaşabilir.
 */
object Kayit {
    private const val MAX_BYTES = 1_000_000L
    private var file: File? = null
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "tani.log")
        log("— Uygulama başladı (sürüm ${appVersion(context)}, Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL})")
    }

    @Synchronized
    fun log(message: String) {
        Log.i("SesliKitap", message)
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                val tail = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText(tail)
            }
            f.appendText("${fmt.format(Date())} [${Thread.currentThread().name.take(18)}] $message\n")
        } catch (_: Exception) {
        }
    }

    fun error(message: String, e: Throwable) =
        log("$message: ${e.javaClass.simpleName}: ${e.message}" + (e.cause?.let { " ← ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    /** Kaydın son kısmı (paylaşmak için). */
    @Synchronized
    fun text(maxChars: Int = 60_000): String = try {
        file?.takeIf { it.exists() }?.readText()?.takeLast(maxChars) ?: ""
    } catch (e: Exception) {
        "Kayıt okunamadı: ${e.message}"
    }

    @Synchronized
    fun clear() {
        try { file?.writeText("") } catch (_: Exception) {}
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
