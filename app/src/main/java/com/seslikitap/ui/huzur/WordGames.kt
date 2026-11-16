@file:OptIn(ExperimentalLayoutApi::class)

package com.seslikitap.ui.huzur

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.container
import com.seslikitap.data.QuoteEntity
import java.util.Locale
import kotlin.random.Random

private val TR = Locale("tr")
private const val LETTERS = "ABCÇDEFGĞHIİJKLMNOÖPRSŞTUÜVYZ"
private val STOP = setOf("BİRDEN", "DAHA", "AMA", "GİBİ", "KADAR", "SONRA", "ÖNCE", "ŞİMDİ", "NEDEN", "BUNU", "ŞUNU", "ONUN", "BENİM", "SENİN", "ONLAR", "BİRAZ", "ÇÜNKÜ", "DİYE", "İÇİN", "ARTIK", "HİÇBİR", "HER", "ŞEYİ", "BÜTÜN")

private fun wordsFrom(text: String): List<String> =
    Regex("[\\p{L}]+").findAll(text).map { it.value.uppercase(TR) }
        .filter { w -> w.length in 4..8 && w.all { it in LETTERS } && w !in STOP }.toList()

// ------------------------------------------------------------------ Kelime avı

@Composable
fun WordSearchPage() {
    val context = LocalContext.current
    val player = context.container.player
    val quotes by context.container.library.quotes.collectAsStateWithLifecycle(emptyList())
    val n = 10
    var round by remember { mutableIntStateOf(0) }
    var grid by remember { mutableStateOf(List(n * n) { ' ' }) }
    val words = remember { mutableStateListOf<String>() }
    val found = remember { mutableStateListOf<String>() }
    val foundCells = remember { mutableStateListOf<Int>() }
    var start by remember { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val source = remember(round) {
        val chText = player.chapter.value?.let { ch -> (0 until minOf(ch.sentences.size, 400)).joinToString(" ") { ch.text(it) } }
        chText ?: quotes.joinToString(" ") { it.text }
    }

    LaunchedEffect(round, source.isNotEmpty()) {
        val freq = wordsFrom(source).groupingBy { it }.eachCount()
        val pool = freq.keys.shuffled(Random(round + System.nanoTime())).take(40)
            .ifEmpty { listOf("KİTAP", "HUZUR", "DENİZ", "YILDIZ", "RÜZGÂR".replace("Â", "A"), "BULUT", "ORMAN", "MASAL") }
        val g = CharArray(n * n) { ' ' }
        val chosen = ArrayList<String>()
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, -1 to 1)
        for (w in pool) {
            if (chosen.size >= 7) break
            for (attempt in 0 until 60) {
                val (dr, dc) = dirs.random()
                val r0 = Random.nextInt(n); val c0 = Random.nextInt(n)
                val cells = w.indices.map { (r0 + dr * it) to (c0 + dc * it) }
                if (cells.any { (r, c) -> r !in 0 until n || c !in 0 until n }) continue
                if (cells.withIndex().any { (i, rc) -> g[rc.first * n + rc.second].let { ch -> ch != ' ' && ch != w[i] } }) continue
                cells.withIndex().forEach { (i, rc) -> g[rc.first * n + rc.second] = w[i] }
                chosen.add(w)
                break
            }
        }
        for (i in g.indices) if (g[i] == ' ') g[i] = LETTERS.random()
        grid = g.toList()
        words.clear(); words.addAll(chosen); found.clear(); foundCells.clear(); start = null
    }

    fun tryPick(a: Int, b: Int) {
        val r1 = a / n; val c1 = a % n; val r2 = b / n; val c2 = b % n
        val dr = Integer.signum(r2 - r1); val dc = Integer.signum(c2 - c1)
        val len = maxOf(kotlin.math.abs(r2 - r1), kotlin.math.abs(c2 - c1)) + 1
        if (!(r1 == r2 || c1 == c2 || kotlin.math.abs(r2 - r1) == kotlin.math.abs(c2 - c1))) return
        val cells = (0 until len).map { (r1 + dr * it) * n + (c1 + dc * it) }
        val s = cells.map { grid[it] }.joinToString("")
        val hit = words.firstOrNull { (it == s || it == s.reversed()) && it !in found }
        if (hit != null) { found.add(hit); foundCells.addAll(cells) }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (player.chapter.value != null) "Kelimeler dinlediğin bölümden. Önce ilk harfe, sonra son harfe dokun." else "Önce kelimenin ilk harfine, sonra son harfine dokun.",
            style = MaterialTheme.typography.bodySmall,
        )
        val primary = MaterialTheme.colorScheme.primary
        val onSurface = MaterialTheme.colorScheme.onSurface
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 8.dp).pointerInput(round) {
                detectTapGestures { p ->
                    val i = (p.y / (size.height / n.toFloat())).toInt().coerceIn(0, n - 1) * n + (p.x / (size.width / n.toFloat())).toInt().coerceIn(0, n - 1)
                    val s0 = start
                    if (s0 == null) start = i else { tryPick(s0, i); start = null }
                }
            },
        ) {
            val cw = size.width / n
            for (i in 0 until n * n) {
                val tl = Offset((i % n) * cw, (i / n) * cw)
                if (i in foundCells) drawRect(primary.copy(alpha = 0.25f), tl, Size(cw, cw))
                if (i == start) drawRect(primary.copy(alpha = 0.45f), tl, Size(cw, cw))
                val t = measurer.measure(grid[i].toString(), TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = onSurface))
                drawText(t, topLeft = Offset(tl.x + (cw - t.size.width) / 2, tl.y + (cw - t.size.height) / 2))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            words.forEach { w ->
                Text(w, textDecoration = if (w in found) TextDecoration.LineThrough else null, color = if (w in found) Color.Gray else MaterialTheme.colorScheme.onSurface)
            }
        }
        if (words.isNotEmpty() && found.size == words.size) Text("Hepsini buldun 🌟", modifier = Modifier.padding(8.dp))
        OutlinedButton(onClick = { round++ }, modifier = Modifier.padding(top = 8.dp)) { Text("Yeni tablo") }
    }
}

// ------------------------------------------------------------------ Eksik kelime / Hangi kitaptan?

@Composable
fun QuoteQuizPage(kind: String) {
    val context = LocalContext.current
    val quotes by context.container.library.quotes.collectAsStateWithLifecycle(emptyList())
    val books by context.container.library.books.collectAsStateWithLifecycle(emptyList())
    var round by remember { mutableIntStateOf(0) }
    var answer by remember { mutableStateOf<String?>(null) }
    var right by remember { mutableIntStateOf(0) }

    val usable = remember(quotes) { quotes.filter { !it.text.startsWith("📝") && it.text.split(" ").size >= 6 } }
    val q: QuoteEntity? = remember(round, usable.size) { usable.randomOrNull() }
    val question = remember(q, kind) {
        q ?: return@remember null
        if (kind == "eksik") {
            val ws = q.text.split(" ")
            val idx = ws.indices.filter { ws[it].trim { c -> !c.isLetter() }.length >= 5 }.randomOrNull() ?: return@remember null
            val word = ws[idx].trim { !it.isLetter() }
            val shown = ws.mapIndexed { i, w -> if (i == idx) w.replace(word, "_____") else w }.joinToString(" ")
            val others = usable.flatMap { it.text.split(" ") }.map { it.trim { c -> !c.isLetter() } }
                .filter { it.length >= 5 && !it.equals(word, true) }.distinct().shuffled().take(3)
            Triple(shown, word, (others + word).shuffled())
        } else {
            val others = (usable.map { it.bookTitle } + books.map { it.title }).distinct().filter { it != q.bookTitle }.shuffled().take(3)
            Triple("“${q.text}”", q.bookTitle, (others + q.bookTitle).shuffled())
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (question == null) {
            Text("Bu oyun kendi alıntılarınla oynanıyor. Okurken bir cümleye basılı tutup \"Alıntılara ekle\" dedikçe oyun zenginleşir (en az birkaç uzun alıntı gerekiyor).")
            return@Column
        }
        Text(if (kind == "eksik") "Boşluğa hangi kelime gelir?" else "Bu alıntı hangi kitaptan?", style = MaterialTheme.typography.titleMedium)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text(question.first, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
        }
        question.third.forEach { opt ->
            val isRight = opt == question.second
            OutlinedButton(
                onClick = { if (answer == null) { answer = opt; if (isRight) right++ } },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            ) {
                Text(
                    opt + when { answer == null -> ""; isRight -> "  ✓"; answer == opt -> "  ✗"; else -> "" },
                    color = when { answer == null -> MaterialTheme.colorScheme.primary; isRight -> Color(0xFF2E7D32); answer == opt -> Color(0xFFB0453A); else -> MaterialTheme.colorScheme.onSurfaceVariant },
                )
            }
        }
        if (answer != null) {
            if (kind == "eksik") Text("— ${q!!.bookTitle}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { answer = null; round++ }) { Text("Sıradaki") }
        }
        Text("$right doğru", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ Günün bilmecesi

private val RIDDLES = listOf(
    "Kat kat giyinir, soyunca insanı ağlatır." to "Soğan",
    "Dişleri var ama ısırmaz, her sabah saçını düzeltir." to "Tarak",
    "Ne kadar çok alırsan o kadar büyür." to "Çukur",
    "Yürür ama ayağı yok; akrebi var, sokmaz." to "Saat",
    "Gece gelir sabah gider, gökte binlercesi göz kırpar." to "Yıldız",
    "Kanatsız uçar, ağızsız uğuldar, gözle görülmez." to "Rüzgâr",
    "Kırılmadan işe yaramaz." to "Yumurta",
    "Bir sarayım var, yüz odası; her odada kırmızı bir tatlı." to "Nar",
    "Bembeyaz bir yorgan yere serilir, güneş açınca kaybolur." to "Kar",
    "Sen onu taşırsın, o da seni taşır." to "Ayakkabı",
    "Ne kadar kurularsa o kadar ıslanır." to "Havlu",
    "Sözü yok ama her soruya cevabı var; açınca konuşur, kapatınca susar." to "Kitap",
    "Ayağı yok gider, kanadı yok uçar, ağzı yok konuşur; zarfın içinde uyur." to "Mektup",
    "Gözü var ama göremez, ipliği kuyruğunda gezer." to "İğne",
    "Senin olduğu hâlde başkaları senden çok kullanır." to "Adın",
    "Gece denizde dikilir, yanıp söner, gemilere yol gösterir." to "Deniz feneri",
    "Elden ele dolaşır, cepten cebe girer; kâğıttan da olur, madenden de." to "Para",
    "Hep gelir ama hiç varmaz." to "Yarın",
    "Dağdan iner, taştan geçer, yorulmaz; denize varınca durulur." to "Dere",
    "Sana benzer ama sen değil; sağını soluna çevirir." to "Ayna",
    "Uyurken gelir, uyanınca gider; bazen güldürür, bazen korkutur." to "Rüya",
    "Başında şapkası, tek ayağı var; yağmurdan sonra ormanda biter." to "Mantar",
    "İki kardeş yan yana durur, birbirini hiç göremez." to "Gözler",
    "Su içmez, ekmek yemez; ama ateşe atarsan ölür." to "Kar tanesi",
    "Kuyruğu var ama hayvan değil, gökte süzülür, ipi sende." to "Uçurtma",
    "Yaz kış yeşil giyinir, iğneleri var ama dikiş dikmez." to "Çam ağacı",
    "Beyaz tarlaya kara tohum ekilir; ekeni de okuyanı da sevinir." to "Yazı",
    "Ağzı var konuşmaz, suyu var içilmez, yatağı var uyunmaz." to "Nehir",
    "Ne kadar çok paylaşırsan o kadar çoğalır." to "Sevgi",
    "Önünde durursun, seni gösterir; arkasına geçersin, hiçbir şey göstermez." to "Ayna",
)

@Composable
fun RiddlePage() {
    val day = java.time.LocalDate.now().toEpochDay().toInt()
    val today = RIDDLES[Math.floorMod(day, RIDDLES.size)]
    val yesterday = RIDDLES[Math.floorMod(day - 1, RIDDLES.size)]
    var show by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Günün bilmecesi", style = MaterialTheme.typography.titleMedium)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text(today.first, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
        }
        Text("Cevabı yarın burada. Gün boyu düşün 🙂", style = MaterialTheme.typography.bodySmall)
        if (show) Text("Cevap: ${today.second}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        else TextButton(onClick = { show = true }) { Text("Beklemeden göster") }
        Text("Dünkü bilmece", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 24.dp))
        Text(yesterday.first, style = MaterialTheme.typography.bodyMedium)
        Text("Cevabı: ${yesterday.second}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
