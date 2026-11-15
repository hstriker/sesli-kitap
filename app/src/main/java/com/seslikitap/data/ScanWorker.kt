package com.seslikitap.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.seslikitap.Kayit
import com.seslikitap.R
import com.seslikitap.container
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Kitaplık taraması: uygulama kapatılsa da sürer, bildirimde ilerleme gösterir. */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = applicationContext.container.library
        val full = inputData.getBoolean(KEY_FULL, false)
        val drive = inputData.getBoolean(KEY_DRIVE, true)
        Kayit.log("Tarama görevi başladı (deneme ${runAttemptCount + 1})")
        try {
            setForeground(info("Kitaplık taranıyor…", 0, 0))
        } catch (e: Exception) {
            // Ön plana alınamazsa (ör. sistem kısıtı) yine de çalışmaya devam et.
            Kayit.error("Tarama ön plana alınamadı", e)
        }
        coroutineScope {
            val progress = launch {
                while (true) {
                    delay(2_000)
                    val st = repo.scan.value
                    if (st.running) {
                        try { setForeground(info(st.phase.ifBlank { "Kitaplık taranıyor…" }, st.processed, st.total)) } catch (_: Exception) {}
                    }
                }
            }
            try {
                repo.runScan(full, drive)
            } catch (e: Throwable) {
                Kayit.error("Tarama görevi durdu", e)
                throw e
            } finally {
                progress.cancel()
            }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info("Kitaplık taranıyor…", 0, 0)

    private fun info(text: String, done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Kitaplık taraması", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle("Kitaplık taranıyor")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .apply { if (total > 0) setProgress(total, done.coerceAtMost(total), false) else setProgress(0, 0, true) }
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "scan"
        private const val NOTIFICATION_ID = 2
        private const val KEY_FULL = "full"
        private const val KEY_DRIVE = "drive"
        private const val WORK_NAME = "kitaplik-tarama"

        fun enqueue(context: Context, full: Boolean, includeDrive: Boolean) {
            val req = OneTimeWorkRequestBuilder<ScanWorker>()
                .setInputData(workDataOf(KEY_FULL to full, KEY_DRIVE to includeDrive))
                .build()
            // Sürmekte olan tarama varsa yenisini başlatma.
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, req)
        }
    }
}
