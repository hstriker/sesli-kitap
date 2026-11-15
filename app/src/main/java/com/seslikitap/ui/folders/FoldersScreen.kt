@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.seslikitap.ui.folders

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.foundation.layout.width
import com.seslikitap.data.DriveUri
import com.seslikitap.container
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.seslikitap.AppContainer
import com.seslikitap.data.FolderEntity
import com.seslikitap.ui.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class FoldersViewModel(container: AppContainer) : ViewModel() {
    private val repo = container.library
    val folders = repo.folders.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val counts = repo.books.map { list -> list.groupingBy { it.folderUri }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val scan = repo.scan

    fun add(uri: android.net.Uri) = repo.addFolder(uri)
    fun remove(uri: String) = repo.removeFolder(uri)
    fun fullRescan() = repo.startScan(full = true)
}

@Composable
/** Ayarlar sekmesi: kitap klasörleri, günlük hedef, tanı kaydı. [onBack] null ise sekme olarak gösterilir. */
fun FoldersScreen(onBack: (() -> Unit)?, onAddDrive: () -> Unit) {
    val vm = appViewModel { FoldersViewModel(it) }
    val folders by vm.folders.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf<FolderEntity?>(null) }
    var showLog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.container.prefs

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.add(uri)
    }

    Scaffold(
        contentWindowInsets = if (onBack == null) androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
        else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pick.launch(null) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Telefondan klasör ekle") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Kitap klasörleri",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
                Text(
                    "Eklediğiniz klasörler ve tüm alt klasörleri taranır. Uygulama her açılışta yeni eklenen " +
                        "ya da değişen kitapları otomatik bulur.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(folders, key = { it.uri }) { f ->
                ListItem(
                    headlineContent = { Text(f.name) },
                    supportingContent = {
                        Text("${counts[f.uri] ?: 0} kitap" + if (DriveUri.isDrive(f.uri)) " · Google Drive" else " · Telefon")
                    },
                    leadingContent = {
                        Icon(
                            if (DriveUri.isDrive(f.uri)) Icons.Default.Cloud else Icons.Default.Folder, null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { confirmRemove = f }) { Icon(Icons.Default.Delete, "Kaldır") }
                    },
                )
                HorizontalDivider()
            }
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    OutlinedButton(onClick = onAddDrive, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Cloud, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Google Drive'dan klasör ekle")
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { vm.fullRescan() }, enabled = !scan.running && folders.isNotEmpty()) {
                        Text(if (scan.running) "Taranıyor…" else "Tüm kitapları baştan tara")
                    }
                    Text(
                        "Kapak ya da başlıklarda sorun görürseniz kullanın. Okuma konumlarınız silinmez.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    CloudVoiceSection()
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    GeminiKeySection()
                    Spacer(Modifier.height(16.dp))
                    GeminiVoiceTrialSection()
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    BackupSection()
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    Text("Günlük dinleme hedefi", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Ana sayfadaki halka bu hedefe göre dolar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    var goal by remember { mutableStateOf(prefs.dailyGoalMin) }
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(10, 15, 30, 45, 60, 90, 120).forEach { m ->
                            androidx.compose.material3.FilterChip(
                                selected = goal == m,
                                onClick = { goal = m; prefs.dailyGoalMin = m },
                                label = { Text(if (m < 60) "$m dk" else "${m / 60} sa" + if (m % 60 != 0) " ${m % 60} dk" else "") },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.TextButton(onClick = { showLog = true }) {
                        Text("Tanı kaydı (sorun bildirmek için)")
                    }
                    Text(
                        "Sesli Kitap · sürüm " + runCatching {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        }.getOrNull().orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    Spacer(Modifier.height(96.dp))
                }
            }
        }
    }

    if (showLog) {
        val text = remember { com.seslikitap.Kayit.text() }
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("Tanı kaydı") },
            text = {
                Column {
                    Text(
                        "\"Kopyala\"ya basıp sohbete yapıştırın ya da \"Paylaş\" ile gönderin.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text.takeLast(4000).ifBlank { "Kayıt boş" },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.height(320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState(Int.MAX_VALUE)),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Sesli Kitap tanı kaydı", text.takeLast(30_000)))
                    android.widget.Toast.makeText(context, "Kopyalandı", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("Kopyala") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text.takeLast(60_000))
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "Tanı kaydını paylaş"))
                }) { Text("Paylaş") }
            },
        )
    }

    confirmRemove?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Klasör kaldırılsın mı?") },
            text = {
                Text("\"${f.name}\" kitaplıktan kaldırılacak. Dosyalarınız silinmez, sadece uygulamadaki listeden çıkar.")
            },
            confirmButton = {
                TextButton(onClick = { vm.remove(f.uri); confirmRemove = null }) { Text("Kaldır") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("Vazgeç") }
            },
        )
    }
}

@Composable
private fun GeminiKeySection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.container.prefs
    var key by remember { mutableStateOf(prefs.geminiKey.orEmpty()) }
    var saved by remember { mutableStateOf(false) }
    Column {
        Text("Yapay zekâ özeti (Gemini)", style = MaterialTheme.typography.titleSmall)
        Text(
            "\"Nerede kalmıştık?\" özeti için ücretsiz bir Gemini anahtarı: aistudio.google.com/apikey → " +
                "Create API key. Anahtar sadece bu telefonda saklanır. Özet için kitabın kaldığın yere kadar " +
                "olan son kısmı Google'a gönderilir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.OutlinedTextField(
            value = key,
            onValueChange = { key = it; saved = false },
            placeholder = { Text("AIza…") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        androidx.compose.material3.TextButton(onClick = { prefs.geminiKey = key.ifBlank { null }; saved = true }) {
            Text(if (saved) "Kaydedildi ✓" else "Kaydet")
        }
    }
}
