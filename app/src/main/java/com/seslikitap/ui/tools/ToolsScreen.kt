@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.seslikitap.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.container
import com.seslikitap.data.BookEntity
import com.seslikitap.data.BookStatus
import com.seslikitap.data.Journal
import com.seslikitap.data.LibraryTools
import com.seslikitap.player.AiCache
import com.seslikitap.player.BookAi
import com.seslikitap.player.Gemini
import com.seslikitap.player.formatDuration
import com.seslikitap.ui.BookCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kitaplık araçları: öneri, hafta sonu, seriler, çiftler, günlük, okuma listesi. */
@Composable
fun ToolsScreen(kind: String, mood: String?, onBack: () -> Unit, onOpenBook: (Long) -> Unit) {
    val title = when (kind) {
        "recommend" -> if (mood != null) "Bugün için öneriler" else "Bana kitap öner"
        "weekend" -> "Hafta sonuna yetecekler"
        "series" -> "Serilerim"
        "duplicates" -> "Çift kitaplar"
        "journal" -> "Kitap günlüğüm"
        "readinglist" -> "Okuma listem"
        else -> ""
    }
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
                "recommend" -> Recommend(mood, onOpenBook)
                "weekend" -> Weekend(onOpenBook)
                "series" -> Series(onOpenBook)
                "duplicates" -> Duplicates(onOpenBook)
                "journal" -> JournalList(onOpenBook)
                "readinglist" -> ReadingList(onOpenBook)
            }
        }
    }
}

@Composable
private fun BookLine(b: BookEntity, subtitle: String?, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(b.coverPath, b.title, b.author, Modifier.width(44.dp).height(64.dp).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (b.author.isNotBlank()) Text(b.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

// ------------------------------------------------------------------ Kitap öner (Gemini)

@Composable
private fun Recommend(mood: String?, onOpenBook: (Long) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val books by container.library.books.collectAsStateWithLifecycle(emptyList())
    var result by remember { mutableStateOf<List<Pair<BookEntity, String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableStateOf(0) }

    LaunchedEffect(round, books.isNotEmpty()) {
        if (books.isEmpty()) return@LaunchedEffect
        result = null
        error = null
        try {
            val ok = books.filter { it.status == BookStatus.OK }
            val journal = withContext(Dispatchers.IO) { Journal.all(context) }
            val liked = ok.filter { it.finished || it.favorite || (journal[it.id]?.rating ?: 0) >= 4 }
                .sortedByDescending { it.finishedAt ?: it.lastOpenedAt ?: 0L }.take(40)
                .map { it.title + if (it.author.isNotBlank()) " (${it.author})" else "" }
            val pool = ok.filter { !it.finished && it.progress < 0.05f }.shuffled(java.util.Random(round.toLong() + System.currentTimeMillis() / 86_400_000L)).take(350)
            if (pool.isEmpty()) { error = "Okunmamış kitap kalmamış gibi görünüyor."; return@LaunchedEffect }
            val day = java.time.LocalDate.now().toString()
            val text = AiCache.getOrPut(context, "oneri|$day|$mood|$round") {
                Gemini.generate(BookAi.key(context), BookAi.recommendPrompt(liked, pool.map { "${it.id}|${it.title}|${it.author}" }, mood), 0.7)
            }
            val byId = ok.associateBy { it.id }
            result = text.lines().mapNotNull { line ->
                val id = line.substringBefore('|').trim().filter { it.isDigit() }.toLongOrNull() ?: return@mapNotNull null
                val b = byId[id] ?: return@mapNotNull null
                b to line.substringAfter('|', "").trim()
            }.distinctBy { it.first.id }
            if (result!!.isEmpty()) error = "Öneri anlaşılamadı; tekrar dene."
        } catch (e: Exception) {
            error = e.message ?: "Öneri alınamadı"
        }
    }

    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        mood?.let { item { Text("Ruh hâlin: $it", modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary) } }
        when {
            error != null -> item { Text(error!!, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            result == null -> item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text("Arşivin taranıyor…")
                }
            }
            else -> items(result!!, key = { it.first.id }) { (b, why) -> BookLine(b, why, onClick = { onOpenBook(b.id) }) }
        }
        item { TextButton(onClick = { round++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Başka öner") } }
    }
}

// ------------------------------------------------------------------ Hafta sonuna yetecekler

@Composable
private fun Weekend(onOpenBook: (Long) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val books by container.library.books.collectAsStateWithLifecycle(emptyList())
    var hours by remember { mutableStateOf(5) }
    val cps = container.prefs.charsPerSecond
    val rate = container.prefs.speechRate
    val list = remember(books, hours) {
        books.filter { it.status == BookStatus.OK && !it.finished }
            .mapNotNull { b -> LibraryTools.remainingMs(context, b, cps, rate)?.let { b to it } }
            .filter { it.second in 20 * 60_000L..hours * 3_600_000L }
            .sortedWith(compareByDescending<Pair<BookEntity, Long>> { it.first.progress > 0f }.thenByDescending { it.second })
            .take(60)
    }
    Column {
        Text(
            "Kalan dinleme süresi seçtiğin süreye sığan kitaplar (tahmini).",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            listOf(3, 5, 8, 12).forEach { h ->
                FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text("$h saat") })
            }
        }
        LazyColumn {
            items(list, key = { it.first.id }) { (b, ms) ->
                BookLine(b, "≈ ${formatDuration(ms)}" + if (b.progress > 0f) " kaldı (%${(b.progress * 100).toInt()} dinlendi)" else "", onClick = { onOpenBook(b.id) })
            }
        }
    }
}

// ------------------------------------------------------------------ Seriler

@Composable
private fun Series(onOpenBook: (Long) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val books by container.library.books.collectAsStateWithLifecycle(emptyList())
    val series = remember(books) { LibraryTools.series(books) }
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        if (series.isEmpty()) item { Text("Kitaplığında seri bulunamadı.", modifier = Modifier.padding(16.dp)) }
        series.forEach { s ->
            item(key = "s-${s.name}") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                        Text(
                            if (s.missing.isEmpty()) "${s.books.size} kitap, arada eksik yok"
                            else "${s.books.size} kitap · arşivinde eksik: ${s.missing.joinToString(", ")}. kitap",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (s.missing.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        s.books.forEach { b ->
                            BookLine(b, b.seriesIndex?.let { "${it.toInt()}. kitap" + if (b.finished) " · bitti ✅" else "" }, onClick = { onOpenBook(b.id) })
                        }
                        val known = info[s.name]
                        if (known != null) Text(known, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                        else TextButton(onClick = {
                            info = info + (s.name to "Soruluyor…")
                            scope.launch {
                                val t = try {
                                    AiCache.getOrPut(context, "seri|${s.name}") {
                                        Gemini.generate(BookAi.key(context), BookAi.seriesPrompt(s.name, s.books.map { it.title }), 0.2)
                                    }
                                } catch (e: Exception) { e.message ?: "Alınamadı" }
                                info = info + (s.name to t)
                            }
                        }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Serinin bütün kitapları neler? (Gemini)") }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Çift kitaplar

@Composable
private fun Duplicates(onOpenBook: (Long) -> Unit) {
    val container = LocalContext.current.container
    val all by container.library.allBooks.collectAsStateWithLifecycle(emptyList())
    val hidden by container.library.hidden.collectAsStateWithLifecycle()
    val groups = remember(all) { LibraryTools.duplicates(all) }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Text(
                "Aynı kitabın birden fazla kopyası. Fazlalıkları gizleyebilirsin: dosyalar silinmez, yalnızca uygulamada görünmez.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (groups.isEmpty()) item { Text("Çift kitap bulunamadı 👍", modifier = Modifier.padding(16.dp)) }
        groups.forEach { g ->
            item(key = "g-${g.first().id}") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    g.forEach { b ->
                        val isHidden = b.id in hidden
                        BookLine(
                            b,
                            "${b.relativePath}${b.fileName} · ${b.sizeBytes / 1024} KB" + (if (b.progress > 0f) " · %${(b.progress * 100).toInt()} dinlendi" else "") +
                                if (isHidden) " · gizli" else "",
                            onClick = { onOpenBook(b.id) },
                        ) {
                            TextButton(onClick = { container.library.setHidden(b.id, !isHidden) }) { Text(if (isHidden) "Göster" else "Gizle") }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Kitap günlüğü

@Composable
private fun JournalList(onOpenBook: (Long) -> Unit) {
    val context = LocalContext.current
    val books by context.container.library.books.collectAsStateWithLifecycle(emptyList())
    var journal by remember { mutableStateOf<Map<Long, Journal.Entry>>(emptyMap()) }
    LaunchedEffect(Unit) { journal = withContext(Dispatchers.IO) { Journal.all(context) } }
    val fmt = remember { java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale("tr")) }
    val rows = remember(books, journal) {
        val byId = books.associateBy { it.id }
        journal.mapNotNull { (id, e) -> byId[id]?.let { it to e } }
            .sortedByDescending { it.second.finished ?: it.second.started ?: 0L }
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        if (rows.isEmpty()) item { Text("Günlüğün henüz boş. Kitaplara başladıkça ve bitirdikçe burada birikir.", modifier = Modifier.padding(16.dp)) }
        items(rows, key = { it.first.id }) { (b, e) ->
            val dates = listOfNotNull(e.started?.let { "Başladın: ${fmt.format(java.util.Date(it))}" }, e.finished?.let { "Bitirdin: ${fmt.format(java.util.Date(it))}" }).joinToString(" · ")
            val stars = if (e.rating > 0) "★".repeat(e.rating) + "☆".repeat(5 - e.rating) + "  " else ""
            BookLine(b, stars + dates + if (e.note.isNotBlank()) "\n“${e.note}”" else "", onClick = { onOpenBook(b.id) })
        }
    }
}

// ------------------------------------------------------------------ Okuma listesi

@Composable
private fun ReadingList(onOpenBook: (Long) -> Unit) {
    val container = LocalContext.current.container
    val prefs = container.prefs
    val books by container.library.books.collectAsStateWithLifecycle(emptyList())
    var ids by remember { mutableStateOf(prefs.readingList) }
    fun save(v: List<Long>) { ids = v; prefs.readingList = v }
    val byId = remember(books) { books.associateBy { it.id } }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Text(
                "Bir kitap bitince sıradaki burada gösterilir. Kitap sayfasından \"Okuma listesine ekle\" ile eklenir.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (ids.isEmpty()) item { Text("Listen boş.", modifier = Modifier.padding(16.dp)) }
        ids.forEachIndexed { i, id ->
            val b = byId[id] ?: return@forEachIndexed
            item(key = "r-$id") {
                BookLine(b, "${i + 1}. sırada", onClick = { onOpenBook(id) }) {
                    Row {
                        IconButton(onClick = { if (i > 0) save(ids.toMutableList().apply { add(i - 1, removeAt(i)) }) }) { Icon(Icons.Default.KeyboardArrowUp, "Yukarı") }
                        IconButton(onClick = { if (i < ids.size - 1) save(ids.toMutableList().apply { add(i + 1, removeAt(i)) }) }) { Icon(Icons.Default.KeyboardArrowDown, "Aşağı") }
                        IconButton(onClick = { save(ids - id) }) { Icon(Icons.Default.Close, "Çıkar") }
                    }
                }
            }
        }
    }
}
