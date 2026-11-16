@file:OptIn(ExperimentalLayoutApi::class)

package com.seslikitap.ui.huzur

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.container
import com.seslikitap.data.BookEntity
import com.seslikitap.data.CalmStore
import com.seslikitap.data.LibraryTools
import com.seslikitap.player.AiCache
import com.seslikitap.player.AudioSession
import com.seslikitap.player.BookAi
import com.seslikitap.player.CalmAi
import com.seslikitap.player.Gemini
import com.seslikitap.ui.BookCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

private fun yearOf(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).year }

/**
 * Kitap başına Gemini'den kısa bilgi (geçtiği ülke, yayın yılı); 50'şerli toplu istek, sonuç kitap başına saklanır.
 * Bilinmeyenler "-" ya da "?" olarak saklanır, tekrar sorulmaz.
 */
private suspend fun bookFacts(context: Context, books: List<BookEntity>, kind: String, prompt: (List<String>) -> String): Map<Long, String> {
    val out = HashMap<Long, String>()
    val missing = ArrayList<BookEntity>()
    for (b in books) AiCache.get(context, "$kind|${b.id}")?.let { out[b.id] = it } ?: missing.add(b)
    if (missing.isNotEmpty()) {
        val key = BookAi.key(context)
        for (chunk in missing.chunked(50)) {
            val text = Gemini.generate(key, prompt(chunk.map { "${it.id}|${it.title}|${it.author}" }), 0.1)
            val got = text.lines().mapNotNull { l ->
                val id = l.substringBefore('|').trim().filter { it.isDigit() }.toLongOrNull() ?: return@mapNotNull null
                id to l.substringAfter('|', "").trim()
            }.toMap()
            for (b in chunk) {
                val v = got[b.id] ?: "?"
                AiCache.put(context, "$kind|${b.id}", v)
                out[b.id] = v
            }
        }
    }
    return out
}

private fun flag(iso: String): String =
    if (iso.length != 2 || !iso.all { it in 'A'..'Z' }) "🏳️" else iso.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

// ------------------------------------------------------------------ Okuma haritası

@Composable
fun ReadingMapPage(onOpenBook: (Long) -> Unit) {
    val context = LocalContext.current
    val books by context.container.library.books.collectAsStateWithLifecycle(emptyList())
    var places by remember { mutableStateOf<Map<Long, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val read = remember(books) { books.filter { it.finished || it.progress > 0.2f } }
    LaunchedEffect(read.size) {
        if (read.isEmpty()) { places = emptyMap(); return@LaunchedEffect }
        try { places = bookFacts(context, read, "yer", CalmAi::placesPrompt) } catch (e: Exception) { error = e.message }
    }
    PageColumn {
        val p = places
        when {
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            p == null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 12.dp)); Text("Kitapların geçtiği yerler bulunuyor…") }
            else -> {
                val byCountry = HashMap<Pair<String, String>, MutableList<BookEntity>>()
                read.forEach { b ->
                    p[b.id]?.split(';')?.forEach { part ->
                        val iso = part.substringBefore(':').trim().uppercase()
                        val name = part.substringAfter(':', "").trim()
                        if (iso.length == 2 && name.isNotBlank()) byCountry.getOrPut(iso to name) { mutableListOf() }.add(b)
                    }
                }
                Text("Kitaplarınla ${byCountry.size} ülkeye gittin 🌍", style = MaterialTheme.typography.titleLarge)
                Text("Dinlediğin ve bitirdiğin kitapların geçtiği yerler (Gemini'nin bilgisine göre).", style = MaterialTheme.typography.bodySmall)
                byCountry.entries.sortedByDescending { it.value.size }.forEach { (c, list) ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text("${flag(c.first)}  ${c.second} · ${list.size} kitap", style = MaterialTheme.typography.titleSmall)
                        list.forEach { b -> Text("   ${b.title}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { onOpenBook(b.id) }) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Kitap rafı odası

@Composable
fun ShelfRoomPage(onOpenBook: (Long) -> Unit) {
    val context = LocalContext.current
    val books by context.container.library.books.collectAsStateWithLifecycle(emptyList())
    val done = remember(books) { books.filter { it.finished }.sortedByDescending { it.finishedAt ?: 0L } }
    Column(Modifier.fillMaxSize().background(Color(0xFF3B2A20)).padding(12.dp)) {
        Text("Bitirdiğin ${done.size} kitap", style = MaterialTheme.typography.titleMedium, color = Color(0xFFF3E3CF))
        if (done.isEmpty()) Text("Bir kitabı bitirdiğinde buraya, rafına yerleşir.", color = Color(0xFFE0CDB5), modifier = Modifier.padding(top = 12.dp))
        androidx.compose.foundation.lazy.LazyColumn {
            items(done.chunked(4).size) { row ->
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp, start = 6.dp, end = 6.dp)) {
                        done.chunked(4)[row].forEach { b ->
                            BookCover(
                                b.coverPath, b.title, b.author,
                                Modifier.weight(1f).aspectRatio(0.68f).clip(RoundedCornerShape(3.dp)).clickable { onOpenBook(b.id) },
                            )
                        }
                        repeat(4 - done.chunked(4)[row].size) { Spacer(Modifier.weight(1f)) }
                    }
                    Box(Modifier.fillMaxWidth().height(14.dp).background(Color(0xFF8B5E3C)))
                    Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0xFF5C3B24)))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Yıllık okuma kartı

private data class YearStats(
    val year: Int, val books: List<BookEntity>, val hours: Int, val authors: List<Pair<String, Int>>,
    val quote: String?, val word: String?,
)

@Composable
fun YearCardPage() {
    val context = LocalContext.current
    val container = context.container
    val books by container.library.books.collectAsStateWithLifecycle(emptyList())
    val days by container.library.listening.collectAsStateWithLifecycle(emptyList())
    val quotes by container.library.quotes.collectAsStateWithLifecycle(emptyList())
    var year by remember { mutableIntStateOf(java.time.Year.now().value) }
    var words by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    LaunchedEffect(Unit) { words = withContext(Dispatchers.IO) { CalmStore.list(context, CalmStore.WORDS) } }
    val stats = remember(books, days, quotes, words, year) {
        val fin = books.filter { yearOf(it.finishedAt) == year }
        YearStats(
            year, fin,
            (days.filter { it.day.startsWith("$year-") }.sumOf { it.ms } / 3_600_000L).toInt(),
            fin.filter { it.author.isNotBlank() }.groupingBy { it.author }.eachCount().toList().sortedByDescending { it.second }.take(3),
            quotes.filter { yearOf(it.createdAt) == year && !it.text.startsWith("📝") }.maxByOrNull { it.text.length.coerceAtMost(160) }?.let { "“${it.text.take(180)}” — ${it.bookTitle}" },
            words.filter { yearOf(it.optLong("t")) == year }.groupingBy { it.optString("w").lowercase() }.eachCount().maxByOrNull { it.value }?.key,
        )
    }
    PageColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0 until 3).map { java.time.Year.now().value - it }.forEach { y -> FilterChip(selected = year == y, onClick = { year = y }, label = { Text("$y") }) }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF243B55)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("${stats.year} okuma yolculuğum", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text("${stats.books.size} kitap · ${stats.hours} saat dinleme", style = MaterialTheme.typography.titleMedium, color = Color(0xFFF2D58C), modifier = Modifier.padding(top = 8.dp))
                if (stats.authors.isNotEmpty()) Text("En çok: " + stats.authors.joinToString(", ") { "${it.first} (${it.second})" }, color = Color.White, modifier = Modifier.padding(top = 6.dp))
                stats.word?.let { Text("Yılın hissi: $it", color = Color(0xFFB8E0D2), modifier = Modifier.padding(top = 6.dp)) }
                stats.quote?.let { Text(it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp)) {
                    stats.books.take(12).forEach { b -> BookCover(b.coverPath, b.title, b.author, Modifier.width(44.dp).height(64.dp).clip(RoundedCornerShape(3.dp))) }
                }
            }
        }
        Button(onClick = { shareYearCard(context, stats) }) { Text("Görsel olarak paylaş") }
    }
}

private fun shareYearCard(context: Context, s: YearStats) {
    val w = 1080; val h = 1350
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.drawColor(0xFF243B55.toInt())
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 72f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
    var y = 180f
    c.drawText("${s.year}", 80f, y, p); y += 100
    p.textSize = 54f; p.typeface = Typeface.SERIF
    c.drawText("okuma yolculuğum", 80f, y, p); y += 140
    p.color = 0xFFF2D58C.toInt(); p.textSize = 64f
    c.drawText("${s.books.size} kitap · ${s.hours} saat", 80f, y, p); y += 110
    p.color = android.graphics.Color.WHITE; p.textSize = 40f
    if (s.authors.isNotEmpty()) { c.drawText("En çok: " + s.authors.joinToString(", ") { it.first }.take(46), 80f, y, p); y += 80 }
    s.word?.let { p.color = 0xFFB8E0D2.toInt(); c.drawText("Yılın hissi: $it", 80f, y, p); y += 90 }
    s.quote?.let { q ->
        p.color = 0xDDFFFFFF.toInt(); p.textSize = 38f; p.typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        val words = q.split(" "); var line = ""
        for (wd in words) {
            if (p.measureText("$line $wd") > w - 160) { c.drawText(line.trim(), 80f, y, p); y += 54; line = "" }
            line += " $wd"
            if (y > h - 260) break
        }
        if (line.isNotBlank() && y <= h - 260) { c.drawText(line.trim(), 80f, y, p); y += 54 }
    }
    p.color = 0x99FFFFFF.toInt(); p.textSize = 30f; p.typeface = Typeface.SANS_SERIF
    c.drawText("Sesli Kitap", 80f, h - 80f, p)
    val dir = File(context.cacheDir, "paylas").apply { mkdirs() }
    val f = File(dir, "okuma-${s.year}.png")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".paylas", f)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Paylaş",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

// ------------------------------------------------------------------ Kendi ödüllerin

private val AWARDS = listOf(
    "Yılın kitabı" to true, "Yılın yazarı" to false, "En sevdiğim karakter" to false,
    "En çok güldüğüm an" to false, "En çok duygulandığım an" to false, "Yeni yıla taşıdığım cümle" to false,
)

@Composable
fun AwardsPage() {
    val context = LocalContext.current
    val books by context.container.library.books.collectAsStateWithLifecycle(emptyList())
    var year by remember { mutableIntStateOf(java.time.Year.now().value) }
    var all by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { CalmStore.list(context, "oduller") } }
    fun value(cat: String) = all.lastOrNull { it.optInt("y") == year && it.optString("c") == cat }?.optString("v").orEmpty()
    fun set(cat: String, v: String) {
        all = all.filterNot { it.optInt("y") == year && it.optString("c") == cat } + JSONObject().put("y", year).put("c", cat).put("v", v)
        CalmStore.save(context, "oduller", all)
    }
    val finishedThisYear = remember(books, year) { books.filter { yearOf(it.finishedAt) == year } }
    PageColumn {
        Text("Yılın sonunda kendi ödüllerini ver; yıllar içinde bir hatıra arşivi olur.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            (0 until 4).map { java.time.Year.now().value - it }.forEach { y -> FilterChip(selected = year == y, onClick = { year = y }, label = { Text("$y") }) }
        }
        AWARDS.forEach { (cat, isBook) ->
            Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("🏆 $cat", style = MaterialTheme.typography.titleSmall)
                    if (isBook) {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { open = true }) { Text(value(cat).ifBlank { "Kitap seç" }) }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                (finishedThisYear.ifEmpty { books.filter { it.finished } }).take(60).forEach { b ->
                                    DropdownMenuItem(text = { Text(b.title) }, onClick = { set(cat, b.title); open = false })
                                }
                            }
                        }
                    } else {
                        var text by remember(year) { mutableStateOf(value(cat)) }
                        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
                        if (text != value(cat)) TextButton(onClick = { set(cat, text.trim()) }) { Text("Kaydet") }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Rozetler

@Composable
fun BadgesPage() {
    val context = LocalContext.current
    val container = context.container
    val prefs = container.prefs
    val books by container.library.books.collectAsStateWithLifecycle(emptyList())
    val days by container.library.listening.collectAsStateWithLifecycle(emptyList())
    val quotes by container.library.quotes.collectAsStateWithLifecycle(emptyList())
    var vocab by remember { mutableIntStateOf(0) }
    var countries by remember { mutableIntStateOf(0) }
    LaunchedEffect(books.size) {
        withContext(Dispatchers.IO) {
            vocab = CalmStore.list(context, CalmStore.VOCAB).size
            countries = books.mapNotNull { AiCache.get(context, "yer|${it.id}") }
                .flatMap { v -> v.split(';').map { it.substringBefore(':').trim() }.filter { it.length == 2 } }.toSet().size
        }
    }
    val finished = books.filter { it.finished }
    val hours = days.sumOf { it.ms } / 3_600_000L
    val seriesDone = LibraryTools.series(books).any { s -> s.books.size >= 2 && s.books.all { it.finished } }
    val authorFriend = finished.filter { it.author.isNotBlank() }.groupingBy { it.author }.eachCount().values.any { it >= 3 }
    val badges = listOf(
        Triple("🌱", "İlk adım", finished.isNotEmpty()) to "İlk kitabını bitir",
        Triple("📚", "On kitap", finished.size >= 10) to "10 kitap bitir",
        Triple("🏛️", "Elli kitap", finished.size >= 50) to "50 kitap bitir",
        Triple("⏳", "Yüz saat", hours >= 100) to "Toplam 100 saat dinle",
        Triple("🦉", "Gece kuşu", prefs.flag("gece")) to "Gece yarısından sonra dinle",
        Triple("🌅", "Erkenci", prefs.flag("erkenci")) to "Sabah 5-7 arası dinle",
        Triple("🧵", "Seriyi tamamlayan", seriesDone) to "Bir serinin bütün kitaplarını bitir",
        Triple("🤝", "Yazar dostu", authorFriend) to "Aynı yazardan 3 kitap bitir",
        Triple("🌍", "Dünya gezgini", countries >= 5) to "Kitaplarınla 5 ülkeye git",
        Triple("✍️", "Alıntı koleksiyoncusu", quotes.size >= 50) to "50 alıntı kaydet",
        Triple("📒", "Kelime avcısı", vocab >= 20) to "Defterine 20 kelime ekle",
        Triple("🔥", "Bir hafta birlikte", com.seslikitap.ui.home.streakOf(days) >= 7) to "7 gün üst üste dinle",
    )
    PageColumn {
        Text("Rozetler kendiliğinden gelir; kaçırınca hiçbir şey kaybetmezsin.", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 3, modifier = Modifier.padding(top = 12.dp)) {
            badges.forEach { (b, hint) ->
                val (emoji, name, got) = b
                Card(
                    Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = if (got) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                ) {
                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(emoji, style = MaterialTheme.typography.headlineMedium, color = if (got) Color.Unspecified else Color.Gray.copy(alpha = 0.4f))
                        Text(name, style = MaterialTheme.typography.labelLarge)
                        Text(if (got) "✓" else hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Kitap ağacı

@Composable
fun TreePage() {
    val context = LocalContext.current
    val books by context.container.library.books.collectAsStateWithLifecycle(emptyList())
    val finished = remember(books) { books.filter { it.finished }.sortedBy { it.finishedAt ?: 0L } }
    val thisYear = java.time.Year.now().value
    // Dalların uçları (sabit, her açılışta aynı ağaç)
    val tips = remember {
        val out = ArrayList<Pair<Offset, Offset>>() // (başlangıç, bitiş) normalize
        fun grow(x: Float, y: Float, angle: Double, len: Float, depth: Int) {
            val x2 = x + (cos(angle) * len).toFloat(); val y2 = y - (sin(angle) * len).toFloat()
            out.add(Offset(x, y) to Offset(x2, y2))
            if (depth == 0) return
            val r = Random(depth * 31 + (x * 1000).toInt())
            grow(x2, y2, angle + 0.35 + r.nextDouble() * 0.2, len * 0.74f, depth - 1)
            grow(x2, y2, angle - 0.35 - r.nextDouble() * 0.2, len * 0.74f, depth - 1)
        }
        grow(0.5f, 0.98f, Math.PI / 2, 0.24f, 7)
        out
    }
    val leafSpots = remember(tips) {
        val r = Random(7)
        tips.takeLast(256).flatMap { (_, e) -> List(3) { Offset(e.x + (r.nextFloat() - 0.5f) * 0.04f, e.y + (r.nextFloat() - 0.5f) * 0.04f) } }.shuffled(Random(3))
    }
    val sway by rememberInfiniteTransition(label = "ruzgar").animateFloat(-1f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "sallan")
    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (finished.isEmpty()) "İlk kitabını bitirdiğinde ağacında ilk yaprak açar 🌱" else "Ağacında ${finished.size} yaprak var", style = MaterialTheme.typography.titleMedium)
        Text("Bu yılın yaprakları yeşil, önceki yıllarınki sonbahar renginde.", style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val w = size.width; val h = size.height
            fun at(o: Offset) = Offset(o.x * w + (1f - o.y) * sway * 6f, o.y * h)
            tips.forEachIndexed { i, (s, e) ->
                val depthWidth = (14f * (1f - i.toFloat() / tips.size)).coerceAtLeast(1.5f)
                drawLine(Color(0xFF6B4A33), at(s), at(e), strokeWidth = depthWidth)
            }
            finished.forEachIndexed { i, b ->
                val spot = leafSpots.getOrNull(i) ?: return@forEachIndexed
                val y = yearOf(b.finishedAt) ?: thisYear
                val col = when (thisYear - y) { 0 -> Color(0xFF5DAA4F); 1 -> Color(0xFFB5C24B); 2 -> Color(0xFFE0A33A); else -> Color(0xFFC0623B) }
                drawCircle(col, radius = 9f, center = at(spot))
            }
            drawLine(Color(0xFF7A9A5A), Offset(0f, h * 0.985f), Offset(w, h * 0.985f), strokeWidth = 6f)
        }
    }
}

// ------------------------------------------------------------------ Edebiyat zaman çizgisi

@Composable
fun TimelinePage(onOpenBook: (Long) -> Unit) {
    val context = LocalContext.current
    val books by context.container.library.books.collectAsStateWithLifecycle(emptyList())
    val read = remember(books) { books.filter { it.finished || it.progress > 0.2f } }
    var years by remember { mutableStateOf<Map<Long, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(read.size) {
        if (read.isEmpty()) { years = emptyMap(); return@LaunchedEffect }
        try { years = bookFacts(context, read, "yil", CalmAi::yearsPrompt) } catch (e: Exception) { error = e.message }
    }
    PageColumn {
        val y = years
        when {
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            y == null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 12.dp)); Text("Yayın yılları bulunuyor…") }
            else -> {
                val dated = read.mapNotNull { b -> y[b.id]?.toIntOrNull()?.let { b to it } }.sortedBy { it.second }
                Text("Okuduklarınla zamanda yolculuk", style = MaterialTheme.typography.titleLarge)
                val byCentury = dated.groupBy { (_, yr) -> if (yr < 0) "MÖ" else "${yr / 100 + 1}. yüzyıl" }
                byCentury.entries.maxByOrNull { it.value.size }?.let { Text("En çok zamanı ${it.key} içinde geçirdin.", style = MaterialTheme.typography.bodyMedium) }
                byCentury.forEach { (century, list) ->
                    Text(century, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp))
                    list.forEach { (b, yr) ->
                        Row(Modifier.clickable { onOpenBook(b.id) }.padding(vertical = 3.dp)) {
                            Text(if (yr < 0) "MÖ ${-yr}" else "$yr", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
                            Text(b.title + if (b.author.isNotBlank()) " — ${b.author}" else "", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                val unknown = read.size - dated.size
                if (unknown > 0) Text("$unknown kitabın yılı bilinmiyor.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ Gece gökyüzü

private class Constellation(val name: String, val stars: List<Offset>, val lines: List<Pair<Int, Int>>)

private val SKY = listOf(
    Constellation("Büyük Ayı", listOf(Offset(.08f, .18f), Offset(.15f, .20f), Offset(.21f, .23f), Offset(.27f, .25f), Offset(.29f, .33f), Offset(.37f, .34f), Offset(.38f, .26f)), listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 3)),
    Constellation("Küçük Ayı", listOf(Offset(.55f, .06f), Offset(.59f, .10f), Offset(.62f, .14f), Offset(.64f, .19f), Offset(.70f, .21f), Offset(.71f, .16f), Offset(.65f, .14f)), listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 3)),
    Constellation("Kraliçe (Kassiopeia)", listOf(Offset(.72f, .34f), Offset(.77f, .41f), Offset(.82f, .36f), Offset(.87f, .43f), Offset(.92f, .37f)), listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4)),
    Constellation("Avcı (Orion)", listOf(Offset(.26f, .49f), Offset(.20f, .54f), Offset(.32f, .55f), Offset(.24f, .62f), Offset(.26f, .63f), Offset(.28f, .64f), Offset(.21f, .73f), Offset(.33f, .72f)), listOf(0 to 1, 0 to 2, 1 to 3, 2 to 5, 3 to 4, 4 to 5, 3 to 6, 5 to 7)),
    Constellation("Kuğu", listOf(Offset(.60f, .52f), Offset(.60f, .60f), Offset(.60f, .68f), Offset(.60f, .75f), Offset(.52f, .58f), Offset(.68f, .62f)), listOf(0 to 1, 1 to 2, 2 to 3, 4 to 1, 1 to 5)),
    Constellation("Lir", listOf(Offset(.44f, .41f), Offset(.46f, .46f), Offset(.50f, .46f), Offset(.49f, .51f), Offset(.45f, .51f)), listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 1)),
    Constellation("Akrep", listOf(Offset(.38f, .80f), Offset(.41f, .83f), Offset(.43f, .86f), Offset(.44f, .90f), Offset(.47f, .93f), Offset(.51f, .94f), Offset(.54f, .92f), Offset(.55f, .88f)), listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 7)),
    Constellation("Aslan", listOf(Offset(.78f, .60f), Offset(.80f, .56f), Offset(.84f, .55f), Offset(.86f, .58f), Offset(.84f, .63f), Offset(.83f, .68f), Offset(.92f, .66f), Offset(.95f, .70f), Offset(.88f, .71f)), listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 8, 8 to 7, 7 to 6, 6 to 4)),
)

@Composable
fun NightSkyPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Constellation?>(null) }
    var myth by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val bg = remember { List(160) { i -> Random(i * 13).let { r -> Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) } } }
    val t by rememberInfiniteTransition(label = "gok").animateFloat(0f, 1f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "parilti")
    Column(Modifier.fillMaxSize().background(Color(0xFF070B1A))) {
        Canvas(
            Modifier.fillMaxWidth().weight(1f).pointerInput(Unit) {
                detectTapGestures { p ->
                    val nx = p.x / size.width; val ny = p.y / size.height
                    val hit = SKY.minByOrNull { c -> c.stars.minOf { hypot(it.x - nx, it.y - ny) } }
                    if (hit != null && hit.stars.minOf { hypot(it.x - nx, it.y - ny) } < 0.08f) {
                        selected = hit; myth = null; busy = true
                        scope.launch {
                            myth = try { CalmAi.ask(context, "takimyildiz|${hit.name}", CalmAi.constellation(hit.name), 0.5) } catch (e: Exception) { e.message }
                            busy = false
                        }
                    }
                }
            },
        ) {
            val w = size.width; val h = size.height
            bg.forEach { (x, y, a) ->
                val alpha = 0.2f + 0.5f * (0.5f + 0.5f * sin((t * 2 * Math.PI + a * 10).toFloat()))
                drawCircle(Color.White.copy(alpha = alpha * a), radius = 1f + a * 1.5f, center = Offset(x * w, y * h))
            }
            SKY.forEach { c ->
                val sel = c === selected
                c.lines.forEach { (a, b) -> drawLine(Color(if (sel) 0xCCF2D58C else 0x44AFC6FF), Offset(c.stars[a].x * w, c.stars[a].y * h), Offset(c.stars[b].x * w, c.stars[b].y * h), strokeWidth = if (sel) 3f else 1.5f) }
                c.stars.forEach { s -> drawCircle(Color(if (sel) 0xFFFFF1C2 else 0xFFE8EEFF), radius = if (sel) 6f else 4f, center = Offset(s.x * w, s.y * h)) }
            }
        }
        Column(Modifier.fillMaxWidth().background(Color(0xFF0E1530)).padding(16.dp)) {
            val s = selected
            if (s == null) Text("Bir takımyıldızına dokun; efsanesini anlatayım.", color = Color.White.copy(alpha = 0.8f))
            else {
                Text(s.name, style = MaterialTheme.typography.titleMedium, color = Color(0xFFF2D58C))
                if (busy) CircularProgressIndicator(Modifier.padding(8.dp))
                myth?.let { m ->
                    Text(m, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { AudioSession.play(context, s.name, m) }) { Text("Dinle", color = Color(0xFFF2D58C)) }
                }
            }
            Text("Şekiller yaklaşıktır.", color = Color.White.copy(alpha = 0.4f), style = MaterialTheme.typography.labelSmall)
        }
    }
}
