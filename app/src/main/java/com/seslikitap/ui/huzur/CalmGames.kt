@file:OptIn(ExperimentalLayoutApi::class)

package com.seslikitap.ui.huzur

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.container
import com.seslikitap.data.BookEntity
import com.seslikitap.ui.BookCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random

// ------------------------------------------------------------------ Zen bahçesi

@Composable
fun ZenGardenPage() {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val stones = remember { mutableStateListOf<Pair<Offset, Float>>() }
    val grain = remember { List(900) { Random(it).let { r -> Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) } } }
    val haptic = LocalHapticFeedback.current

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Parmağınla kuma desen çiz. Taş koymak için bir yere uzun bas.", style = MaterialTheme.typography.bodySmall)
        Canvas(
            Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp).clip(RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { current = listOf(it) },
                        onDragEnd = { if (current.size > 1) strokes.add(current); current = emptyList() },
                        onDrag = { change, _ -> current = current + change.position },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        stones.add(it to (18f + Random.nextFloat() * 22f))
                    })
                },
        ) {
            drawRect(Color(0xFFE9DFC9))
            grain.forEach { (x, y, a) -> drawCircle(Color(0x22806A48), radius = 1f + a, center = Offset(x * size.width, y * size.height)) }
            fun rake(points: List<Offset>) {
                if (points.size < 2) return
                val p = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } }
                listOf(46f to Color(0x33806A48), 34f to Color(0xFFE9DFC9), 24f to Color(0x2A806A48), 14f to Color(0xFFEDE4D0), 5f to Color(0x22806A48)).forEach { (w, c) ->
                    drawPath(p, c, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            strokes.forEach { rake(it) }
            rake(current)
            stones.forEach { (c, r) ->
                // taşın etrafında halkalar
                for (k in 3 downTo 1) drawCircle(Color(0x1E806A48), radius = r + k * 12f, center = c, style = Stroke(width = 5f))
                drawCircle(Brush.radialGradient(listOf(Color(0xFF9A948C), Color(0xFF5E5A55)), center = c - Offset(r / 3, r / 3), radius = r * 1.6f), radius = r, center = c)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) { Text("Son çizgiyi sil") }
            OutlinedButton(onClick = { strokes.clear(); stones.clear() }) { Text("Kumu düzle") }
        }
    }
}

// ------------------------------------------------------------------ Kabarcık patlatma

@Composable
fun BubblesPage() {
    val cols = 6
    val rows = 9
    val popped = remember { mutableStateMapOf<Int, Boolean>() }
    val haptic = LocalHapticFeedback.current
    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(cols / rows.toFloat()).clip(RoundedCornerShape(12.dp)).background(Color(0xFFDDEFF5))
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val cw = size.width / cols.toFloat()
                        val ch = size.height / rows.toFloat()
                        val i = (p.y / ch).toInt() * cols + (p.x / cw).toInt()
                        if (i in 0 until cols * rows && popped[i] != true) {
                            popped[i] = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                },
        ) {
            val cw = size.width / cols
            val ch = size.height / rows
            val r = minOf(cw, ch) * 0.42f
            for (i in 0 until cols * rows) {
                val c = Offset((i % cols + 0.5f) * cw, (i / cols + 0.5f) * ch)
                if (popped[i] == true) {
                    drawCircle(Color(0x33607D8B), radius = r * 0.9f, center = c, style = Stroke(width = 2f))
                } else {
                    drawCircle(Brush.radialGradient(listOf(Color(0xCCFFFFFF), Color(0x6690CAF9)), center = c - Offset(r / 3, r / 3), radius = r * 1.3f), radius = r, center = c)
                    drawCircle(Color(0x55607D8B), radius = r, center = c, style = Stroke(width = 1.5f))
                }
            }
        }
        Text("${popped.size} / ${cols * rows}", modifier = Modifier.padding(8.dp))
        OutlinedButton(onClick = { popped.clear() }) { Text("Yeni naylon") }
    }
}

// ------------------------------------------------------------------ Mandala boyama

private val PALETTE = listOf(
    Color(0xFFE8A0A8), Color(0xFFF2C38B), Color(0xFFF4E19C), Color(0xFFA8D5BA), Color(0xFF9CC9E0),
    Color(0xFFB7A7E0), Color(0xFF6E8B74), Color(0xFF3F5E78), Color.White,
)

@Composable
fun MandalaPage() {
    var segments by remember { mutableIntStateOf(12) }
    val rings = 6
    val fills = remember { mutableStateMapOf<Pair<Int, Int>, Color>() }
    var color by remember { mutableStateOf(PALETTE[0]) }
    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(segments) {
                detectTapGestures { p ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val maxR = minOf(size.width, size.height) / 2f * 0.96f
                    val d = hypot(p.x - c.x, p.y - c.y)
                    if (d > maxR) return@detectTapGestures
                    val ring = (d / (maxR / rings)).toInt().coerceIn(0, rings - 1)
                    var ang = atan2(p.y - c.y, p.x - c.x) + PI / 2
                    if (ang < 0) ang += 2 * PI
                    val seg = if (ring == 0) 0 else ((ang / (2 * PI)) * segments).toInt() % segments
                    fills[ring to seg] = color
                }
            },
        ) {
            val c = center
            val maxR = size.minDimension / 2f * 0.96f
            val rw = maxR / rings
            for (ring in 0 until rings) {
                if (ring == 0) {
                    drawCircle(fills[0 to 0] ?: Color.White, radius = rw, center = c)
                    drawCircle(Color(0xFF555555), radius = rw, center = c, style = Stroke(width = 2f))
                    continue
                }
                val r = rw * ring + rw / 2
                val sweep = 360f / segments
                for (s in 0 until segments) {
                    val start = -90f + s * sweep
                    drawArc(
                        fills[ring to s] ?: Color.White, start, sweep, useCenter = false,
                        topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = Stroke(width = rw),
                    )
                }
                drawCircle(Color(0xFF555555), radius = rw * (ring + 1), center = c, style = Stroke(width = 2f))
                for (s in 0 until segments) {
                    val a = Math.toRadians((-90.0 + s * 360.0 / segments))
                    drawLine(
                        Color(0xFF555555),
                        start = Offset(c.x + (rw * ring) * kotlin.math.cos(a).toFloat(), c.y + (rw * ring) * kotlin.math.sin(a).toFloat()),
                        end = Offset(c.x + (rw * (ring + 1)) * kotlin.math.cos(a).toFloat(), c.y + (rw * (ring + 1)) * kotlin.math.sin(a).toFloat()),
                        strokeWidth = 2f,
                    )
                }
                // her halkada küçük yaprak süsleri
                if (ring % 2 == 0) for (s in 0 until segments) {
                    val a = Math.toRadians((-90.0 + (s + 0.5) * 360.0 / segments))
                    drawCircle(Color(0x55555555), radius = rw * 0.12f, center = Offset(c.x + r * kotlin.math.cos(a).toFloat(), c.y + r * kotlin.math.sin(a).toFloat()))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            PALETTE.forEach { p ->
                Box(
                    Modifier.size(if (color == p) 40.dp else 32.dp).clip(CircleShape).background(p)
                        .clickable { color = p },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(8, 12, 16).forEach { n -> FilterChip(selected = segments == n, onClick = { segments = n; fills.clear() }, label = { Text("$n dilim") }) }
            OutlinedButton(onClick = { fills.clear() }) { Text("Temizle") }
        }
    }
}

// ------------------------------------------------------------------ Kapaktan yapboz

@Composable
fun PuzzlePage(onOpenBook: (Long) -> Unit) {
    val context = LocalContext.current
    val books by context.container.library.books.collectAsStateWithLifecycle(emptyList())
    var n by remember { mutableIntStateOf(3) }
    var round by remember { mutableIntStateOf(0) }
    var book by remember { mutableStateOf<BookEntity?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val tiles = remember { mutableStateListOf<Int>() }
    var moves by remember { mutableIntStateOf(0) }

    LaunchedEffect(round, n, books.isNotEmpty()) {
        val withCover = books.filter { it.coverPath != null }
        val b = withCover.randomOrNull() ?: return@LaunchedEffect
        book = b
        image = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(b.coverPath)?.asImageBitmap() }.getOrNull() }
        // Çözülebilir karıştırma: çözülmüş hâlden rastgele geçerli hamleler
        val t = (0 until n * n).toMutableList()
        var empty = n * n - 1
        var prev = -1
        repeat(n * n * 40) {
            val r = empty / n; val c = empty % n
            val opts = listOfNotNull(
                if (r > 0) empty - n else null, if (r < n - 1) empty + n else null,
                if (c > 0) empty - 1 else null, if (c < n - 1) empty + 1 else null,
            ).filter { it != prev }
            val pick = opts.random()
            t[empty] = t[pick]; t[pick] = n * n - 1
            prev = empty; empty = pick
        }
        tiles.clear(); tiles.addAll(t); moves = 0
    }
    val solved = tiles.isNotEmpty() && tiles.withIndex().all { it.index == it.value }

    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val img = image
        if (img == null || tiles.size != n * n) {
            Text("Kapaklı bir kitap aranıyor…")
        } else {
            Canvas(
                Modifier.fillMaxWidth(0.9f).aspectRatio(img.width / img.height.toFloat()).clip(RoundedCornerShape(8.dp))
                    .pointerInput(n, round) {
                        detectTapGestures { p ->
                            if (tiles.size == n * n && tiles.withIndex().all { it.index == it.value }) return@detectTapGestures
                            val c = (p.x / (size.width / n.toFloat())).toInt().coerceIn(0, n - 1)
                            val r = (p.y / (size.height / n.toFloat())).toInt().coerceIn(0, n - 1)
                            val i = r * n + c
                            val e = tiles.indexOf(n * n - 1)
                            val adjacent = (i / n == e / n && kotlin.math.abs(i - e) == 1) || kotlin.math.abs(i - e) == n
                            if (adjacent) { tiles[e] = tiles[i]; tiles[i] = n * n - 1; moves++ }
                        }
                    },
            ) {
                val tw = size.width / n
                val th = size.height / n
                val sw = img.width / n
                val sh = img.height / n
                for (pos in 0 until n * n) {
                    val t = tiles[pos]
                    if (t == n * n - 1 && !solved) {
                        drawRect(Color(0xFF2B2B2B), topLeft = Offset((pos % n) * tw, (pos / n) * th), size = Size(tw, th))
                        continue
                    }
                    drawImage(
                        img, srcOffset = IntOffset((t % n) * sw, (t / n) * sh), srcSize = IntSize(sw, sh),
                        dstOffset = IntOffset(((pos % n) * tw).toInt(), ((pos / n) * th).toInt()), dstSize = IntSize(tw.toInt() + 1, th.toInt() + 1),
                    )
                    if (!solved) drawRect(Color(0x55000000), topLeft = Offset((pos % n) * tw, (pos / n) * th), size = Size(tw, th), style = Stroke(width = 2f))
                }
            }
            Text(if (solved) "Tamamlandı! 🎉 ($moves hamle)" else "$moves hamle", modifier = Modifier.padding(8.dp))
            if (solved) book?.let { b ->
                Text("\"${b.title}\"", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { onOpenBook(b.id) }) { Text("Bu kitabı dinlemek ister misin?") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            listOf(3, 4).forEach { k -> FilterChip(selected = n == k, onClick = { n = k }, label = { Text("${k}x$k") }) }
            OutlinedButton(onClick = { round++ }) { Text("Başka kapak") }
        }
    }
}

// ------------------------------------------------------------------ Hafıza kartları

@Composable
fun MemoryPage() {
    val context = LocalContext.current
    val books by context.container.library.books.collectAsStateWithLifecycle(emptyList())
    var round by remember { mutableIntStateOf(0) }
    val cards = remember { mutableStateListOf<BookEntity>() }
    val open = remember { mutableStateListOf<Int>() }
    val matched = remember { mutableStateListOf<Int>() }
    var tries by remember { mutableIntStateOf(0) }

    LaunchedEffect(round, books.isNotEmpty()) {
        val pool = books.filter { it.coverPath != null }.shuffled().take(6)
        if (pool.size < 6) return@LaunchedEffect
        cards.clear(); cards.addAll((pool + pool).shuffled()); open.clear(); matched.clear(); tries = 0
    }
    LaunchedEffect(open.toList()) {
        if (open.size == 2) {
            tries++
            if (cards[open[0]].id == cards[open[1]].id) { matched.addAll(open); open.clear() }
            else { delay(900); open.clear() }
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (cards.size < 12) {
            Text("Bu oyun için kapağı olan en az 6 kitap gerekiyor.")
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
                cards.forEachIndexed { i, b ->
                    val faceUp = i in open || i in matched
                    Box(
                        Modifier.weight(1f).aspectRatio(0.7f).clip(RoundedCornerShape(8.dp))
                            .background(if (faceUp) Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                            .clickable(enabled = !faceUp && open.size < 2) { open.add(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (faceUp) BookCover(b.coverPath, b.title, b.author, Modifier.fillMaxSize())
                        else Text("📖", style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
            Text(if (matched.size == cards.size) "Hepsini buldun 🌟 ($tries deneme)" else "$tries deneme", modifier = Modifier.padding(8.dp))
        }
        OutlinedButton(onClick = { round++ }) { Text("Yeniden") }
    }
}
