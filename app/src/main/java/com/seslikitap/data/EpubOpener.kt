package com.seslikitap.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.seslikitap.core.ByteArraySource
import com.seslikitap.core.FileChannelSource
import com.seslikitap.core.ZipArchive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

object EpubOpener {

    private const val MAX_IN_MEMORY = 300L * 1024 * 1024

    /**
     * content:// adresindeki EPUB'ı açar. Önce dosyayı belleğe almadan rastgele erişimle açmayı dener;
     * kaynak buna izin vermezse (ör. bulut sağlayıcıları) dosyayı belleğe okur.
     */
    fun open(context: Context, uri: Uri): ZipArchive {
        val resolver = context.contentResolver
        try {
            val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Dosya açılamadı")
            val fis = FileInputStream(pfd.fileDescriptor)
            val channel = fis.channel
            val usable = try { channel.size() > 0 } catch (e: Exception) { false }
            if (usable) {
                return ZipArchive.open(FileChannelSource(channel) {
                    try { fis.close() } catch (_: Exception) {}
                    try { pfd.close() } catch (_: Exception) {}
                })
            }
            try { fis.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        } catch (e: SecurityException) {
            throw IOException("Dosyaya erişim izni yok. Klasörü yeniden ekleyin.", e)
        } catch (e: IOException) {
            // Rastgele erişim başarısız: aşağıda akış olarak dene. Dosya ZIP değilse orada da hata verir.
            if (e.message?.contains("EPUB/ZIP") == true) throw e
        }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_IN_MEMORY) throw IOException("Dosya çok büyük")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw IOException("Dosya açılamadı")
        return ZipArchive.open(ByteArraySource(bytes))
    }
}

object CoverStore {

    private const val TARGET_HEIGHT = 480

    fun dir(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }

    fun fileFor(context: Context, bookUri: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(bookUri.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir(context), "$hash.jpg")
    }

    /** Kapağı küçültüp JPEG olarak kaydeder. Başarısız olursa null. */
    fun save(context: Context, bookUri: String, imageBytes: ByteArray): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= TARGET_HEIGHT) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
            if (bmp == null) {
                null
            } else {
                val scaled = if (bmp.height > TARGET_HEIGHT * 1.5) {
                    val w = (bmp.width * TARGET_HEIGHT.toFloat() / bmp.height).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bmp, w, TARGET_HEIGHT, true).also { if (it !== bmp) bmp.recycle() }
                } else bmp
                val file = fileFor(context, bookUri)
                FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                scaled.recycle()
                file.absolutePath
            }
        }
    } catch (e: Throwable) {
        // OutOfMemoryError dahil: kapak olmadan devam
        null
    }
}
