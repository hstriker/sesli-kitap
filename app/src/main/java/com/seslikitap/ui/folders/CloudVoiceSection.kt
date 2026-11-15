package com.seslikitap.ui.folders

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.seslikitap.container
import com.seslikitap.drive.AuthOutcome
import com.seslikitap.player.CloudTtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Ayarlar: doğal ses (Google WaveNet) açma, Google'a bağlanma, ses seçimi ve aylık kullanım. */
@Composable
fun CloudVoiceSection() {
    val context = LocalContext.current
    val container = context.container
    val prefs = container.prefs
    val player = container.player
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(prefs.voiceMode == "cloud") }
    var voice by remember { mutableStateOf(prefs.cloudVoice) }
    var status by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf<Boolean?>(null) }
    val used = remember(status) { prefs.cloudCharsThisMonth() }
    val limit = prefs.cloudMonthlyLimit

    fun handle(r: AuthOutcome, launch: (AuthOutcome.NeedsConsent) -> Unit) {
        when (r) {
            is AuthOutcome.Ok -> { connected = true; status = "Google bağlantısı hazır ✓" }
            is AuthOutcome.NeedsConsent -> launch(r)
            is AuthOutcome.Failed -> { connected = false; status = r.message }
        }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            val r = withContext(Dispatchers.IO) { container.cloudAuth.handleConsentResult(res.data) }
            handle(r) { connected = false; status = "Google onayı tamamlanmadı" }
        }
    }

    fun connect() {
        status = "Bağlanılıyor…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { container.cloudAuth.authorizeBlocking() }
            handle(r) { consent.launch(IntentSenderRequest.Builder(it.intent).build()) }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text("Doğal ses (Google WaveNet)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Kitaplar Google'ın yapay zekâ sesiyle okunur. Ayda ${formatChars(limit)} karaktere kadar ücretsiz " +
                "kotada kalınır; sınır dolunca ya da internet yoksa telefon sesine geçilir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().clickable { enabled = !enabled; player.setCloudVoice(enabled) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Doğal sesi kullan", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it; player.setCloudVoice(it) })
        }
        if (enabled) {
            Button(onClick = { connect() }) {
                Text(if (connected == true) "Bağlantıyı yeniden dene" else "Google'a bağlan")
            }
            status?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall,
                    color = if (connected == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("Ses", style = MaterialTheme.typography.labelLarge)
            CloudTtsEngine.VOICES.forEach { (name, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { voice = name; player.setVoice(name) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = voice == name, onClick = { voice = name; player.setVoice(name) })
                    Text(label)
                }
            }
            Text(
                "Sesleri denemek için bir kitap açıp okuma ekranındaki ayarlardan da değiştirebilirsiniz.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Bu ay: ${formatChars(used)} / ${formatChars(limit)} karakter (yaklaşık ${"%.1f".format(Locale("tr"), used / 700_000.0)} kitap)",
            style = MaterialTheme.typography.bodySmall,
        )
        LinearProgressIndicator(
            progress = { (used.toFloat() / limit).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

private fun formatChars(n: Long): String = when {
    n >= 1_000_000 -> "%.1f milyon".format(Locale("tr"), n / 1_000_000.0)
    n >= 1_000 -> "${n / 1_000} bin"
    else -> "$n"
}
