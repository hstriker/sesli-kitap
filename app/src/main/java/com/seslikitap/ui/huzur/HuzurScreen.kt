@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.seslikitap.ui.huzur

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.player.AudioSession

data class HuzurTile(val kind: String, val emoji: String, val title: String, val subtitle: String)

/** Huzur sekmesindeki bölümler; her paket kendi bölümünü ekler. */
val HUZUR_SECTIONS: List<Pair<String, List<HuzurTile>>> = listOf(
    "Dinlen" to listOf(
        HuzurTile("uyku", "🌙", "Uyku hikâyesi", "Sana özel, sakin bir hikâye"),
        HuzurTile("nefes", "🫧", "Nefes ve gevşeme", "4-7-8, kutu nefesi, nefes balonu, beden taraması"),
    ),
    "Hayal et" to listOf(
        HuzurTile("macera", "🧭", "Sesli macera", "Seçimlerinle ilerleyen hikâye"),
        HuzurTile("ortak", "✍️", "Hikâye ortaklığı", "Sırayla birlikte yazın"),
    ),
    "Kendine" to listOf(
        HuzurTile("aksam", "🎙️", "Akşam notu", "Günün sonunda sesli günlük"),
        HuzurTile("mektup", "💌", "Geleceğe mektup", "Seçtiğin tarihte açılır"),
        HuzurTile("kelimeler", "☁️", "Kapanış kelimelerin", "Dinlemelerin nasıl hissettirdi"),
        HuzurTile("hatirlatici", "🔔", "Nazik hatırlatıcı", "İstediğin saatte, baskısız"),
    ),
) + EXTRA_SECTIONS

@Composable
fun HuzurScreen(onOpen: (String) -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Huzur") }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SessionBar() }
            HUZUR_SECTIONS.forEach { (section, tiles) ->
                item(key = section) {
                    Text(section, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        maxItemsInEachRow = 2,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        tiles.forEach { t ->
                            Card(
                                Modifier.weight(1f).clickable { onOpen(t.kind) },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(t.emoji, fontSize = 26.sp)
                                    Text(t.title, style = MaterialTheme.typography.titleSmall)
                                    Text(t.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (tiles.size % 2 == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Huzur içindeki bir sayfa. */
@Composable
fun HuzurDetail(kind: String, onBack: () -> Unit, onOpenBook: (Long) -> Unit, onOpenReader: (Long) -> Unit) {
    val title = HUZUR_SECTIONS.flatMap { it.second }.firstOrNull { it.kind == kind }?.title ?: ""
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (kind) {
                "uyku" -> SleepStoryPage()
                "nefes" -> BreathPage()
                "macera" -> AdventurePage()
                "ortak" -> CoWritePage()
                "aksam" -> EveningNotePage()
                "mektup" -> LettersPage()
                "kelimeler" -> ClosingWordsPage()
                "hatirlatici" -> ReminderPage()
                else -> ExtraPage(kind, onOpenBook, onOpenReader)
            }
        }
    }
}

/** Çalan sesli içerik (hikâye, masal…) için küçük kumanda. */
@Composable
fun SessionBar() {
    val st by AudioSession.state.collectAsStateWithLifecycle()
    val title = st.title ?: return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (st.preparing) CircularProgressIndicator(Modifier.padding(end = 8.dp).width(20.dp), strokeWidth = 2.dp)
            Text(if (st.preparing) "$title hazırlanıyor…" else title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (!st.preparing) IconButton(onClick = { AudioSession.togglePause() }) {
                Icon(if (st.paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (st.paused) "Devam" else "Duraklat")
            }
            IconButton(onClick = { AudioSession.stop() }) { Icon(Icons.Default.Stop, "Durdur") }
        }
    }
}

/** Sayfa içeriği için kaydırılabilir sütun. */
@Composable
fun PageColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        SessionBar()
        content()
        androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp))
    }
}
