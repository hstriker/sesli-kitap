@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.seslikitap.ui.reader

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TocSheet(onDismiss: () -> Unit, onSelect: (chapter: Int, anchor: String?) -> Unit) {
    val player = LocalContext.current.container.player
    val st by player.state.collectAsStateWithLifecycle()
    val toc = player.bookContent?.epub?.toc.orEmpty()
    val currentIdx = toc.indexOfLast { it.spineIndex <= st.chapterIndex }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIdx - 3).coerceAtLeast(0))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("İçindekiler", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn(state = listState) {
            items(toc.size) { i ->
                val t = toc[i]
                val selected = i == currentIdx
                Text(
                    t.title,
                    style = if (t.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onSelect(t.spineIndex, t.fragment) }
                        .padding(start = (20 + t.depth * 16).dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun BookmarksSheet(bookId: Long, onDismiss: () -> Unit) {
    val container = LocalContext.current.container
    val player = container.player
    val flow = remember(bookId) { container.db.bookmarks().observe(bookId) }
    val bookmarks by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val content = player.bookContent

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Yer imleri", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
        if (bookmarks.isEmpty()) {
            Text(
                "Henüz yer imi yok. Okurken alttaki yer imi düğmesine basarak o anki cümleyi kaydedebilirsiniz.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn {
            items(bookmarks, key = { it.id }) { b ->
                ListItem(
                    headlineContent = { Text(b.excerpt, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(content?.chapterTitle(b.chapterIndex) ?: "Bölüm ${b.chapterIndex + 1}")
                    },
                    trailingContent = {
                        IconButton(onClick = {
                            scope.launch { withContext(Dispatchers.IO) { container.db.bookmarks().delete(b.id) } }
                        }) { Icon(Icons.Default.Delete, "Sil") }
                    },
                    modifier = Modifier.clickable { player.goToBookmark(b); onDismiss() },
                )
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun SettingsSheet(fontSize: Float, onFontSize: (Float) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val player = container.player
    val prefs = container.prefs
    val st by player.state.collectAsStateWithLifecycle()

    var rate by remember { mutableFloatStateOf(st.rate) }
    var pitch by remember { mutableFloatStateOf(st.pitch) }
    var voices by remember { mutableStateOf(player.voices()) }
    var engines by remember { mutableStateOf(player.engines()) }
    var selectedVoice by remember { mutableStateOf(prefs.voiceName) }
    var selectedEngine by remember { mutableStateOf(prefs.ttsEngine) }
    var skipNotes by remember { mutableStateOf(prefs.skipFootnotes) }
    var skipPages by remember { mutableStateOf(prefs.skipPageNumbers) }
    var normalize by remember { mutableStateOf(prefs.normalizeText) }

    // Motor hazır olunca ses listesini yenile
    LaunchedEffect(st.engine) {
        voices = player.voices()
        engines = player.engines()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Ses ayarları", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            Text("Okuma hızı: ${formatRate(rate)}", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = rate,
                onValueChange = { rate = it },
                onValueChangeFinished = { player.setRate(rate) },
                valueRange = 0.5f..3f,
                steps = 49,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { r ->
                    FilterChip(
                        selected = kotlin.math.abs(rate - r) < 0.01f,
                        onClick = { rate = r; player.setRate(r) },
                        label = { Text(formatRate(r)) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Text("Ses tonu: ${formatRate(pitch).removeSuffix("x")}", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = pitch,
                onValueChange = { pitch = it },
                onValueChangeFinished = { player.setPitch(pitch) },
                valueRange = 0.5f..2f,
                steps = 29,
            )
            TextButton(onClick = { pitch = 1f; player.setPitch(1f) }) { Text("Tonu sıfırla") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Ses", style = MaterialTheme.typography.titleSmall)
            RadioRow("Varsayılan Türkçe ses", selectedVoice == null) {
                selectedVoice = null; player.setVoice(null)
            }
            voices.forEach { v ->
                RadioRow(v.label, selectedVoice == v.name) {
                    selectedVoice = v.name; player.setVoice(v.name)
                }
            }
            if (voices.isEmpty()) {
                Text(
                    "Seçilebilir Türkçe ses bulunamadı. Google Konuşma Hizmetleri ayarlarından ek Türkçe sesler indirebilirsiniz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (engines.size > 1) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Seslendirme motoru", style = MaterialTheme.typography.titleSmall)
                RadioRow("Sistem varsayılanı", selectedEngine == null) {
                    selectedEngine = null; player.setEngine(null)
                }
                engines.forEach { (pkg, label) ->
                    RadioRow(label, selectedEngine == pkg) {
                        selectedEngine = pkg; player.setEngine(pkg)
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {}
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Android metin okuma ayarları") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Okuma", style = MaterialTheme.typography.titleSmall)
            SwitchRow("Dipnotları atla", skipNotes) {
                skipNotes = it; prefs.skipFootnotes = it; player.reloadContent()
            }
            SwitchRow("Sayfa numaralarını atla", skipPages) {
                skipPages = it; prefs.skipPageNumbers = it; player.reloadContent()
            }
            SwitchRow("Sayıları ve kısaltmaları Türkçe oku (\"15. yy.\" → \"on beşinci yüzyıl\")", normalize) {
                normalize = it; prefs.normalizeText = it
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Yazı boyutu", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onFontSize((fontSize - 2f).coerceAtLeast(12f)) }) { Text("A−") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { onFontSize((fontSize + 2f).coerceAtMost(34f)) }) { Text("A+") }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SleepSheet(onDismiss: () -> Unit) {
    val player = LocalContext.current.container.player
    val st by player.state.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Uyku zamanlayıcısı", style = MaterialTheme.typography.titleLarge)
            val active = when {
                st.sleepAtChapterEnd -> "Bölüm sonunda duracak"
                st.sleepAt != null -> {
                    val min = ((st.sleepAt!! - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0) + 1
                    "Yaklaşık $min dakika sonra duracak"
                }
                else -> "Kapalı"
            }
            Text(active, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
                    OutlinedButton(onClick = { player.setSleepTimer(m); onDismiss() }) { Text("$m dk") }
                }
                OutlinedButton(onClick = { player.setSleepAtChapterEnd(); onDismiss() }) { Text("Bölüm sonunda") }
            }
            if (st.sleepAt != null || st.sleepAtChapterEnd) {
                TextButton(onClick = { player.cancelSleepTimer(); onDismiss() }) { Text("Zamanlayıcıyı kapat") }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
