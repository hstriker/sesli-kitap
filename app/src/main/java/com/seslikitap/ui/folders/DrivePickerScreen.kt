@file:OptIn(ExperimentalMaterial3Api::class)

package com.seslikitap.ui.folders

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.seslikitap.AppContainer
import com.seslikitap.drive.AuthOutcome
import com.seslikitap.drive.DriveItem
import com.seslikitap.ui.appViewModel
import android.app.PendingIntent
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DriveCrumb(val id: String, val name: String)

data class DrivePickerUi(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val loading: Boolean = false,
    val path: List<DriveCrumb> = listOf(DriveCrumb("root", "Drive'ım")),
    val folders: List<DriveItem> = emptyList(),
    val epubCount: Int = 0,
    val error: String? = null,
    val consent: PendingIntent? = null,
)

class DrivePickerViewModel(private val container: AppContainer) : ViewModel() {
    val ui = MutableStateFlow(DrivePickerUi())

    init { connect() }

    /** Sessizce bağlanmayı dener; onay gerekiyorsa ekranda "Bağlan" düğmesi gösterilir. */
    fun connect(interactive: Boolean = false) {
        ui.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { container.driveAuth.authorizeBlocking() }
            onAuth(r, interactive)
        }
    }

    fun onConsentResult(data: Intent?) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { container.driveAuth.handleConsentResult(data) }
            onAuth(r, interactive = false)
        }
    }

    private fun onAuth(r: AuthOutcome, interactive: Boolean) {
        when (r) {
            is AuthOutcome.Ok -> {
                ui.update { it.copy(connected = true, connecting = false, consent = null) }
                load()
            }
            // Sessiz denemede onay ekranını kendiliğinden açma; kullanıcı "Bağlan"a basınca açılır.
            is AuthOutcome.NeedsConsent -> ui.update {
                it.copy(connecting = false, connected = false, consent = if (interactive) r.intent else null)
            }
            is AuthOutcome.Failed -> ui.update { it.copy(connecting = false, error = r.message) }
        }
    }

    fun consentLaunched() = ui.update { it.copy(consent = null) }

    fun open(folder: DriveItem) {
        ui.update { it.copy(path = it.path + DriveCrumb(folder.id, folder.name)) }
        load()
    }

    fun goTo(index: Int) {
        ui.update { it.copy(path = it.path.take(index + 1)) }
        load()
    }

    fun back(): Boolean {
        if (ui.value.path.size <= 1) return false
        goTo(ui.value.path.size - 2)
        return true
    }

    fun load() {
        val current = ui.value.path.last()
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { container.drive.listChildren(current.id) }
                if (ui.value.path.last() != current) return@launch
                ui.update {
                    it.copy(
                        loading = false,
                        folders = items.filter { i -> i.isFolder }.distinctBy { i -> i.id },
                        epubCount = items.count { i -> i.isEpub },
                    )
                }
            } catch (e: Exception) {
                ui.update { it.copy(loading = false, error = e.message ?: "Drive okunamadı") }
            }
        }
    }

    val linkError = MutableStateFlow<String?>(null)
    val linkBusy = MutableStateFlow(false)

    /** drive.google.com klasör bağlantısından (ya da doğrudan klasör kimliğinden) ekler. */
    fun addByLink(text: String, onDone: () -> Unit) {
        val id = folderIdFromLink(text)
        if (id == null) {
            linkError.value = "Bu bir Drive klasör bağlantısına benzemiyor. Örnek: drive.google.com/drive/folders/1AbC…"
            return
        }
        linkBusy.value = true
        linkError.value = null
        viewModelScope.launch {
            try {
                val item = withContext(Dispatchers.IO) { container.drive.metadata(id) }
                if (!item.isFolder) {
                    linkError.value = "Bu bağlantı bir klasöre değil, bir dosyaya ait."
                } else {
                    container.library.addDriveFolder(item.id, item.name)
                    onDone()
                }
            } catch (e: Exception) {
                linkError.value = e.message ?: "Klasör bulunamadı"
            } finally {
                linkBusy.value = false
            }
        }
    }

    fun addCurrent() {
        val c = ui.value.path.last()
        container.library.addDriveFolder(c.id, c.name)
    }
}

/** "https://drive.google.com/drive/folders/<id>?usp=..." , "...?id=<id>" ya da çıplak kimlik. */
fun folderIdFromLink(text: String): String? {
    val t = text.trim()
    Regex("""folders/([A-Za-z0-9_-]{10,})""").find(t)?.let { return it.groupValues[1] }
    Regex("""[?&]id=([A-Za-z0-9_-]{10,})""").find(t)?.let { return it.groupValues[1] }
    if (Regex("""[A-Za-z0-9_-]{15,}""").matches(t)) return t
    return null
}

@Composable
fun DrivePickerScreen(onBack: () -> Unit, onAdded: () -> Unit) {
    val vm = appViewModel { DrivePickerViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val linkError by vm.linkError.collectAsStateWithLifecycle()
    val linkBusy by vm.linkBusy.collectAsStateWithLifecycle()
    var linkDialog by remember { mutableStateOf(false) }
    var linkText by remember { mutableStateOf("") }

    if (linkDialog) {
        AlertDialog(
            onDismissRequest = { linkDialog = false },
            title = { Text("Klasör bağlantısıyla ekle") },
            text = {
                Column {
                    Text(
                        "Drive'da kitap klasörünü açın ve adres çubuğundaki bağlantıyı kopyalayın " +
                            "(…drive.google.com/drive/folders/…). Ya da Drive uygulamasında klasör → ⋮ → Bağlantıyı kopyala.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = linkText,
                        onValueChange = { linkText = it },
                        placeholder = { Text("https://drive.google.com/drive/folders/…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    linkError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = linkText.isNotBlank() && !linkBusy,
                    onClick = { vm.addByLink(linkText) { linkDialog = false; onAdded() } },
                ) { Text(if (linkBusy) "Ekleniyor…" else "Ekle") }
            },
            dismissButton = { TextButton(onClick = { linkDialog = false }) { Text("Vazgeç") } },
        )
    }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        vm.onConsentResult(res.data)
    }
    LaunchedEffect(ui.consent) {
        ui.consent?.let {
            vm.consentLaunched()
            consentLauncher.launch(IntentSenderRequest.Builder(it).build())
        }
    }

    androidx.activity.compose.BackHandler(enabled = ui.path.size > 1) { vm.back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Google Drive'dan ekle") },
                navigationIcon = {
                    IconButton(onClick = { if (!vm.back()) onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                },
                actions = {
                    if (ui.connected) {
                        TextButton(onClick = { linkText = ""; vm.linkError.value = null; linkDialog = true }) {
                            Icon(Icons.Default.Link, null)
                            Spacer(Modifier.size(4.dp))
                            Text("Bağlantıyla ekle")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (ui.connected) {
                Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                        val name = ui.path.last().name
                        Text(
                            if (ui.epubCount > 0) "Bu klasörde ${ui.epubCount} EPUB var. Alt klasörler de taranır."
                            else "Bu klasörde doğrudan EPUB yok; alt klasörler de taranır.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { vm.addCurrent(); onAdded() },
                            enabled = !ui.loading && ui.error == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("\"$name\" klasörünü ekle") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.connecting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                !ui.connected -> ConnectPrompt(ui.error) { vm.connect(interactive = true) }
                else -> {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ui.path.forEachIndexed { i, c ->
                            if (i > 0) Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(18.dp))
                            TextButton(onClick = { vm.goTo(i) }) { Text(c.name, maxLines = 1) }
                        }
                    }
                    HorizontalDivider()
                    ui.error?.let { ErrorCard(it, onRetry = { vm.load() }) }
                    if (ui.loading) {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            if (ui.folders.isEmpty() && ui.error == null) {
                                item {
                                    Text(
                                        "Bu klasörde alt klasör yok.",
                                        modifier = Modifier.padding(24.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(ui.folders, key = { it.id }) { f ->
                                ListItem(
                                    headlineContent = { Text(f.name) },
                                    leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                                    modifier = Modifier.clickable { vm.open(f) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectPrompt(error: String?, onConnect: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Cloud, null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Google Drive'a bağlanın", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Uygulama Drive'ınızı yalnızca okur; hiçbir dosyayı değiştirmez ya da silmez. " +
                "Kitapların sadece başlık ve kapak bilgisi okunur, dinlediğiniz kitap telefona indirilir.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Girişte \"Google bu uygulamayı doğrulamadı\" uyarısı çıkarsa Gelişmiş → Devam et'e dokunun.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onConnect) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, null)
            Spacer(Modifier.size(8.dp))
            Text("Google Drive'a bağlan")
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            ErrorCard(it, onRetry = null)
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: (() -> Unit)?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(message)
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Yeniden dene") }
        }
    }
}
