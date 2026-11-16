package com.seslikitap.ui.folders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.seslikitap.container
import com.seslikitap.player.FocusMode

/** Ayarlar → Dinleme konforu: Benim zamanım (Rahatsız Etmeyin), pil dostu mod. */
@Composable
fun ComfortSection() {
    val context = LocalContext.current
    val prefs = context.container.prefs
    var dnd by remember { mutableStateOf(prefs.myTimeDnd) }
    var access by remember { mutableStateOf(FocusMode.hasAccess(context)) }
    var battery by remember { mutableStateOf(prefs.batteryFriendly) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = FocusMode.hasAccess(context) }

    Column(Modifier.fillMaxWidth()) {
        Text("Dinleme konforu", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth().clickable { dnd = !dnd; prefs.myTimeDnd = dnd }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Benim zamanım")
                Text(
                    "Dinlerken telefon Rahatsız Etmeyin'e geçer, durunca eski hâline döner.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = dnd, onCheckedChange = { dnd = it; prefs.myTimeDnd = it })
        }
        if (dnd && !access) {
            Text(
                "Bunun için telefonun bir kez izin vermesi gerekiyor: açılan listede Sesli Kitap'ı bulup izni aç.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = { FocusMode.openAccessSettings(context) }, modifier = Modifier.padding(top = 4.dp)) {
                Text("İzin ver")
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable { battery = !battery; prefs.batteryFriendly = battery }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Pil dostu mod")
                Text(
                    "Pil %20'nin altına inince ya da güç tasarrufu açıkken sakin ekran durağan olur, " +
                        "Gemini hakkı biriktirme yapılmaz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = battery, onCheckedChange = { battery = it; prefs.batteryFriendly = it })
        }
    }
}
