@file:OptIn(ExperimentalLayoutApi::class)

package com.seslikitap.ui.huzur

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.container
import com.seslikitap.core.TurkishText
import com.seslikitap.data.BookStatus
import com.seslikitap.data.CalmStore
import com.seslikitap.data.Sozluk
import com.seslikitap.player.AiCache
import com.seslikitap.player.AudioSession
import com.seslikitap.player.CalmAi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private fun today() = java.time.LocalDate.now().toString()

@Composable
private fun DailyCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** Gemini'den günlük içerik (gün boyu aynı, saklanır). */
@Composable
private fun DailyAi(title: String, key: String, prompt: String, listenTitle: String) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        try { text = CalmAi.ask(context, "$key|${today()}", prompt, 0.7) } catch (e: Exception) { error = e.message }
    }
    DailyCard(title) {
        when {
            error != null -> Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            text == null -> CircularProgressIndicator(Modifier.padding(8.dp))
            else -> {
                Text(text!!, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { AudioSession.play(context, listenTitle, text!!) }) { Text("Dinle") }
            }
        }
    }
}

// ------------------------------------------------------------------ Günün köşesi

@Composable
fun DailyCornerPage(onOpenReader: (Long) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val quotes by container.library.quotes.collectAsStateWithLifecycle(emptyList())
    val books by container.library.books.collectAsStateWithLifecycle(emptyList())
    val day = java.time.LocalDate.now().toEpochDay()

    PageColumn {
        // Günün alıntısı
        val q = quotes.filter { !it.text.startsWith("📝") }.let { if (it.isEmpty()) null else it[Math.floorMod(day, it.size.toLong()).toInt()] }
        DailyCard("Günün alıntısı") {
            if (q == null) Text("Alıntı ekledikçe her gün biri burada belirir.", style = MaterialTheme.typography.bodyMedium)
            else {
                Text("“${q.text}”", style = MaterialTheme.typography.bodyLarge)
                Text("— ${q.bookTitle}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        // Günün öyküsü / şiiri (arşivden)
        val short = remember(books) {
            val re = Regex("öykü|oyku|hikaye|hikâye|şiir|siir|poem|story|stories|masal|deneme", RegexOption.IGNORE_CASE)
            books.filter { it.status == BookStatus.OK && it.chapterCount > 1 && (re.containsMatchIn(it.title) || re.containsMatchIn(it.relativePath)) }
        }
        DailyCard("Günün öyküsü / şiiri") {
            val b = short.let { if (it.isEmpty()) null else it[Math.floorMod(day, it.size.toLong()).toInt()] }
            if (b == null) Text("Arşivinde öykü ya da şiir kitabı bulunamadı (adında ya da klasöründe \"öykü\", \"şiir\", \"hikâye\" geçen kitaplar).", style = MaterialTheme.typography.bodyMedium)
            else {
                val chapter = Math.floorMod(day * 7, b.chapterCount.toLong()).toInt()
                Text("\"${b.title}\" içinden bugün ${chapter + 1}. bölüm", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    container.player.open(b.id, chapter = chapter)
                    onOpenReader(b.id)
                }) { Text("Aç ve dinle") }
            }
        }
        WordOfDay()
        DailyAi("Günün deyimi", "deyim", CalmAi.idiom(), "Günün deyimi")
        DailyAi("Günün şiiri (eski şairlerimizden)", "siir", CalmAi.poem("Yunus Emre, Karacaoğlan, Pir Sultan Abdal, Kaygusuz Abdal, Dadaloğlu"), "Günün şiiri")
        Text(
            "Deyim ve şiir Gemini tarafından getirilir; nadiren hatalı olabilir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WordOfDay() {
    val context = LocalContext.current
    val container = context.container
    val quotes by container.library.quotes.collectAsStateWithLifecycle(emptyList())
    var result by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var tried by remember { mutableStateOf(false) }
    LaunchedEffect(quotes.size) {
        val cacheKey = "gunun-kelimesi|${today()}"
        AiCache.get(context, cacheKey)?.let { s ->
            val parts = s.split("\n")
            result = parts.first() to parts.drop(1)
            return@LaunchedEffect
        }
        val text = (container.player.chapter.value?.let { ch -> (0 until minOf(ch.sentences.size, 300)).joinToString(" ") { ch.text(it) } } ?: "") +
            " " + quotes.joinToString(" ") { it.text }
        val words = Regex("\\p{L}{7,14}").findAll(text).map { it.value.lowercase(TurkishText.TR) }.distinct().toList()
            .shuffled(java.util.Random(java.time.LocalDate.now().toEpochDay())).take(6)
        for (w in words) {
            val r = runCatching { Sozluk.ara(w) }.getOrNull() ?: continue
            result = r.madde to r.anlamlar.take(3)
            AiCache.put(context, cacheKey, (listOf(r.madde) + r.anlamlar.take(3)).joinToString("\n"))
            break
        }
        tried = true
    }
    DailyCard("Günün kelimesi") {
        val r = result
        if (r == null) Text(if (tried) "Bugün için kelime bulunamadı; bir kitap dinledikçe kelimeler oradan gelir." else "Aranıyor…", style = MaterialTheme.typography.bodyMedium)
        else {
            Text(r.first, style = MaterialTheme.typography.headlineSmall)
            r.second.forEachIndexed { i, m -> Text("${i + 1}. $m", style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = {
                CalmStore.add(context, CalmStore.VOCAB, JSONObject().put("t", System.currentTimeMillis()).put("w", r.first).put("m", r.second.firstOrNull() ?: "").put("b", "Günün kelimesi"))
            }) { Text("Kelime defterime ekle") }
        }
    }
}

// ------------------------------------------------------------------ Masal ve fıkra köşesi

@Composable
fun TalesPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("Bir Türk halk masalı") }
    var text by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    fun load(fresh: Boolean) {
        busy = true; error = null
        scope.launch {
            try { text = CalmAi.ask(context, "masal|$kind|${today()}|$round", CalmAi.folkTale(kind), 0.8, fresh) } catch (e: Exception) { error = e.message } finally { busy = false }
        }
    }
    PageColumn {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Bir Türk halk masalı", "Bir Nasreddin Hoca fıkrası", "Bir Keloğlan masalı", "Bir Anadolu efsanesi").forEach { k ->
                FilterChip(selected = kind == k, onClick = { kind = k; text = null }, label = { Text(k.removePrefix("Bir ")) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Button(onClick = { load(false) }, enabled = !busy) { Text("Anlat") }
            if (text != null) {
                OutlinedButton(onClick = { AudioSession.play(context, kind.removePrefix("Bir "), text!!) }) { Text("Dinle") }
                TextButton(onClick = { round++; load(true) }) { Text("Başka") }
            }
        }
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        text?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
}

// ------------------------------------------------------------------ Merak köşesi

@Composable
fun CuriosityPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var topic by remember { mutableStateOf("") }
    var text by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun go(t: String?) {
        busy = true; error = null
        scope.launch {
            try {
                val r = CalmAi.ask(context, if (t.isNullOrBlank()) null else "merak|${t.trim().lowercase()}", CalmAi.curiosity(t), 0.7)
                text = r
                AudioSession.play(context, "Merak köşesi", r)
            } catch (e: Exception) { error = e.message } finally { busy = false }
        }
    }
    PageColumn {
        Text("Merak ettiğin bir konuyu yaz ya da şaşırtmamı iste; 4-5 dakikalık sakin bir anlatım hazırlarım.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(value = topic, onValueChange = { topic = it }, placeholder = { Text("Ör. Kapadokya peribacaları") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { go(topic) }, enabled = !busy && topic.isNotBlank()) { Text("Anlat") }
            OutlinedButton(onClick = { go(null) }, enabled = !busy) { Text("Beni şaşırt") }
        }
        if (busy) CircularProgressIndicator(Modifier.padding(12.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        text?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp)) }
    }
}

// ------------------------------------------------------------------ Kelime defteri

@Composable
fun VocabPage() {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var quiz by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    var answer by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { items = withContext(Dispatchers.IO) { CalmStore.list(context, CalmStore.VOCAB) } }

    PageColumn {
        Text(
            "Okurken bir kelimeye basılı tutup \"ne demek?\" dediğinde çıkan anlamı \"Deftere ekle\" ile buraya kaydedebilirsin.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (items.size >= 4) {
            OutlinedButton(onClick = { quiz = !quiz; answer = null }, modifier = Modifier.padding(vertical = 8.dp)) {
                Text(if (quiz) "Listeye dön" else "Tekrar oyunu")
            }
        }
        if (quiz && items.size >= 4) {
            val q = remember(round) { items.random() }
            val opts = remember(round) { (items.filter { it !== q }.shuffled().take(3) + q).shuffled() }
            Text("\"${q.optString("w")}\" ne demek?", style = MaterialTheme.typography.titleMedium)
            opts.forEach { o ->
                val right = o === q
                OutlinedButton(onClick = { if (answer == null) answer = o.optString("w") }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        o.optString("m") + when { answer == null -> ""; right -> " ✓"; answer == o.optString("w") -> " ✗"; else -> "" },
                        color = when { answer == null -> MaterialTheme.colorScheme.primary; right -> Color(0xFF2E7D32); answer == o.optString("w") -> Color(0xFFB0453A); else -> MaterialTheme.colorScheme.onSurfaceVariant },
                    )
                }
            }
            if (answer != null) TextButton(onClick = { answer = null; round++ }) { Text("Sıradaki") }
        } else {
            if (items.isEmpty()) Text("Defterin henüz boş.", modifier = Modifier.padding(top = 12.dp))
            items.reversed().forEach { o ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(o.optString("w"), style = MaterialTheme.typography.titleSmall)
                    Text(o.optString("m"), style = MaterialTheme.typography.bodyMedium)
                    if (o.optString("b").isNotBlank()) Text(o.optString("b"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
