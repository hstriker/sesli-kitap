package com.seslikitap.player

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import com.seslikitap.Kayit
import com.seslikitap.Prefs

/**
 * "Benim zamanım": dinlerken Rahatsız Etmeyin. Yalnızca kullanıcı izin verdiyse ve telefon zaten
 * sessizde değilse açar; kapatırken yalnızca kendi açtığını kapatır.
 */
object FocusMode {

    fun hasAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    fun openAccessSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }

    fun enter(context: Context, prefs: Prefs) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.isNotificationPolicyAccessGranted || prefs.dndActiveByUs) return
        if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        try {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            prefs.dndActiveByUs = true
        } catch (e: Exception) {
            Kayit.error("Rahatsız Etmeyin açılamadı", e)
        }
    }

    fun exit(context: Context, prefs: Prefs) {
        if (!prefs.dndActiveByUs) return
        prefs.dndActiveByUs = false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        } catch (e: Exception) {
            Kayit.error("Rahatsız Etmeyin kapatılamadı", e)
        }
    }
}

/** Pil durumu: pil dostu modda animasyon ve önceden hazırlama kısılır. */
object Battery {
    /** Güç tasarrufu açık ya da şarjda değilken pil %20'nin altında. */
    fun isLow(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm?.isPowerSaveMode == true) return true
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return !charging && level >= 0 && level * 100 / scale <= 20
    }

    fun saving(context: Context, prefs: Prefs): Boolean = prefs.batteryFriendly && isLow(context)
}
