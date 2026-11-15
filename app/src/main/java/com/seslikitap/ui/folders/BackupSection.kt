package com.seslikitap.ui.folders

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.seslikitap.data.Backup
import com.seslikitap.data.BackupWorker
import com.seslikitap.drive.AuthOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ayarlar: Drive'a yedekleme ve geri yükleme. */
@Composable
fun BackupSection() {
    val context = LocalContext.current
    val prefs = context.container.prefs
    val backup = remember { Backup(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(prefs.autoBackup) }
    var confirmRestore by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(prefs.lastBackup) }

    /** İzin gerekiyorsa önce onay ekranını açar, sonra [action]'ı çalıştırır. */
    var pending by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            val r = withContext(Dispatchers.IO) { backup.auth.handleConsentResult(res.data) }
            if (r is AuthOutcome.Ok) pending?.invoke() else status = "Google onayı tamamlanmadı"
            pending = null
        }
    }

    fun run(label: String, action: suspend () -> Unit) {
        busy = true
        status = label
        scope.launch {
            val r = withContext(Dispatchers.IO) { backup.auth.authorizeBlocking() }
            when (r) {
                is AuthOutcome.Ok -> try { action() } catch (e: Exception) { status = e.message ?: "Hata" }
                is AuthOutcome.NeedsConsent -> {
                    pending = { try { action() } catch (e: Exception) { status = e.message ?: "Hata" } }
                    consent.launch(IntentSenderRequest.Builder(r.intent).build())
                }
                is AuthOutcome.Failed -> status = r.message
            }
            busy = false
        }
    }

    val fmt = SimpleDateFormat("d MMMM yyyy HH:mm", Locale("tr"))

    Column(Modifier.fillMaxWidth()) {
        Text("Yedekleme (Google Drive)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Kaldığın yerler, yer imleri, alıntılar, favoriler, raflar, istatistikler ve ayarlar Drive'ında gizli bir " +
                "uygulama klasörüne yedeklenir. Telefon değiştirince Drive klasörünü ekleyip geri yüklemen yeterli.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().clickable {
                auto = !auto; prefs.autoBackup = auto; if (auto) BackupWorker.schedule(context)
            }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Her gün otomatik yedekle", modifier = Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = { auto = it; prefs.autoBackup = it; if (it) BackupWorker.schedule(context) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                run("Yedekleniyor…") {
                    backup.upload()
                    last = prefs.lastBackup
                    status = "Yedeklendi ✓"
                }
            }) { Text("Şimdi yedekle") }
            OutlinedButton(enabled = !busy, onClick = { confirmRestore = true }) { Text("Geri yükle") }
        }
        if (last > 0) {
            Text("Son yedek: ${fmt.format(Date(last))}", style = MaterialTheme.typography.bodySmall)
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Yedekten geri yüklensin mi?") },
            text = {
                Text(
                    "Drive'daki yedek bu telefondaki kitaplığa eklenir. Hiçbir şey silinmez; ilerlemelerde en ileri olan alınır. " +
                        "Önce kitap klasörlerini ekleyip taramanın bitmesini bekleyin."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    run("Geri yükleniyor…") {
                        val json = backup.download()
                        status = if (json == null) "Drive'da yedek bulunamadı" else {
                            val n = backup.restore(json)
                            "Geri yüklendi ✓ ($n kitap eşleşti)"
                        }
                    }
                }) { Text("Geri yükle") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Vazgeç") } },
        )
    }
}
