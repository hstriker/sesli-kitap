package com.seslikitap.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/**
 * "Hoparlöre gönder": Android'in ses çıkışı seçicisini açar (Bluetooth hoparlör, kulaklık, telefonun desteklediği
 * yayın cihazları). Seçici açılamazsa Bluetooth ayarları açılır.
 */
object Output {
    fun openSwitcher(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                context.sendBroadcast(
                    Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                        .setPackage("com.android.systemui")
                        .putExtra("package_name", context.packageName)
                )
                return
            } catch (_: Exception) {}
        }
        try {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(context, "Bir hoparlör ya da kulaklık bağla; ses oradan çıkar.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
    }
}
