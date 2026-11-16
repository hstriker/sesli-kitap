@file:OptIn(ExperimentalLayoutApi::class)

package com.seslikitap.ui.huzur

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ------------------------------------------------------------------ Sudoku (süre ve puan yok)

private fun sudokuOk(g: IntArray, i: Int, v: Int): Boolean {
    val r = i / 9; val c = i % 9
    for (k in 0 until 9) if (g[r * 9 + k] == v || g[k * 9 + c] == v) return false
    val br = r / 3 * 3; val bc = c / 3 * 3
    for (dr in 0 until 3) for (dc in 0 until 3) if (g[(br + dr) * 9 + bc + dc] == v) return false
    return true
}

private fun sudokuFill(g: IntArray): Boolean {
    val i = g.indexOf(0)
    if (i < 0) return true
    for (v in (1..9).shuffled()) if (sudokuOk(g, i, v)) {
        g[i] = v
        if (sudokuFill(g)) return true
        g[i] = 0
    }
    return false
}

private fun newSudoku(givens: Int): IntArray {
    val full = IntArray(81)
    sudokuFill(full)
    val p = full.copyOf()
    (0 until 81).shuffled().take(81 - givens).forEach { p[it] = 0 }
    return p
}

@Composable
fun SudokuPage() {
    var level by remember { mutableIntStateOf(40) }
    var fixed by remember { mutableStateOf(newSudoku(40)) }
    val cells = remember { mutableStateListOf<Int>().apply { addAll(fixed.toList()) } }
    var sel by remember { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()

    fun conflict(i: Int): Boolean {
        val v = cells[i]; if (v == 0) return false
        val r = i / 9; val c = i % 9
        for (k in 0 until 9) {
            if (k != c && cells[r * 9 + k] == v) return true
            if (k != r && cells[k * 9 + c] == v) return true
        }
        val br = r / 3 * 3; val bc = c / 3 * 3
        for (dr in 0 until 3) for (dc in 0 until 3) { val j = (br + dr) * 9 + bc + dc; if (j != i && cells[j] == v) return true }
        return false
    }
    val done = cells.none { it == 0 } && cells.indices.none { conflict(it) }

    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val primary = MaterialTheme.colorScheme.primary
        val onSurface = MaterialTheme.colorScheme.onSurface
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(Unit) {
                detectTapGestures { p ->
                    val c = (p.x / (size.width / 9f)).toInt().coerceIn(0, 8)
                    val r = (p.y / (size.height / 9f)).toInt().coerceIn(0, 8)
                    sel = r * 9 + c
                }
            },
        ) {
            val cw = size.width / 9
            val s = sel
            for (i in 0 until 81) {
                val tl = Offset((i % 9) * cw, (i / 9) * cw)
                val sameValue = s != null && cells[s] != 0 && cells[i] == cells[s]
                if (i == s) drawRect(primary.copy(alpha = 0.3f), tl, Size(cw, cw))
                else if (sameValue) drawRect(primary.copy(alpha = 0.12f), tl, Size(cw, cw))
                val v = cells[i]
                if (v != 0) {
                    val color = when { conflict(i) -> Color(0xFFC0504D); fixed[i] != 0 -> onSurface; else -> primary }
                    val t = measurer.measure("$v", TextStyle(fontSize = 20.sp, fontWeight = if (fixed[i] != 0) FontWeight.Bold else FontWeight.Normal, color = color))
                    drawText(t, topLeft = Offset(tl.x + (cw - t.size.width) / 2, tl.y + (cw - t.size.height) / 2))
                }
            }
            for (k in 0..9) {
                val w = if (k % 3 == 0) 4f else 1f
                drawLine(onSurface.copy(alpha = 0.7f), Offset(k * cw, 0f), Offset(k * cw, size.height), w)
                drawLine(onSurface.copy(alpha = 0.7f), Offset(0f, k * cw), Offset(size.width, k * cw), w)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 10.dp)) {
            (1..9).forEach { v ->
                OutlinedButton(onClick = { sel?.let { if (fixed[it] == 0) cells[it] = v } }, modifier = Modifier.size(52.dp)) { Text("$v") }
            }
            OutlinedButton(onClick = { sel?.let { if (fixed[it] == 0) cells[it] = 0 } }) { Text("Sil") }
        }
        if (done) Text("Tamamlandı 🌿", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(42 to "Kolay", 34 to "Orta", 28 to "Zor").forEach { (g, l) ->
                FilterChip(selected = level == g, onClick = {
                    level = g; fixed = newSudoku(g); cells.clear(); cells.addAll(fixed.toList()); sel = null
                }, label = { Text(l) })
            }
        }
    }
}

// ------------------------------------------------------------------ Solitaire (Klondike, dokunarak)

private data class PCard(val suit: Int, val rank: Int, val up: Boolean) {
    val red get() = suit == 1 || suit == 2
    val label get() = (when (rank) { 1 -> "A"; 11 -> "J"; 12 -> "Q"; 13 -> "K"; else -> "$rank" }) + "♠♥♦♣"[suit]
}

private data class Klondike(
    val stock: List<PCard>,
    val waste: List<PCard>,
    val found: List<List<PCard>>,
    val tab: List<List<PCard>>,
)

private sealed interface Pick {
    data object Waste : Pick
    data class Found(val i: Int) : Pick
    data class Tab(val col: Int, val index: Int) : Pick
}

private fun newKlondike(): Klondike {
    val deck = (0 until 4).flatMap { s -> (1..13).map { r -> PCard(s, r, false) } }.shuffled().toMutableList()
    val tab = (0 until 7).map { col -> (0..col).map { deck.removeAt(0) }.mapIndexed { i, c -> c.copy(up = i == col) } }
    return Klondike(deck, emptyList(), List(4) { emptyList() }, tab)
}

private fun canFound(c: PCard, pile: List<PCard>) = if (pile.isEmpty()) c.rank == 1 else pile.last().suit == c.suit && pile.last().rank + 1 == c.rank
private fun canTab(c: PCard, pile: List<PCard>) = if (pile.isEmpty()) c.rank == 13 else pile.last().up && pile.last().red != c.red && pile.last().rank == c.rank + 1

/** Seçilen kartları kaynaktan alır; (kartlar, yeni oyun) — kaynak sütunun açıkta kalan kartı çevrilir. */
private fun take(g: Klondike, p: Pick): Pair<List<PCard>, Klondike>? = when (p) {
    Pick.Waste -> g.waste.lastOrNull()?.let { listOf(it) to g.copy(waste = g.waste.dropLast(1)) }
    is Pick.Found -> g.found[p.i].lastOrNull()?.let { c -> listOf(c) to g.copy(found = g.found.mapIndexed { i, f -> if (i == p.i) f.dropLast(1) else f }) }
    is Pick.Tab -> {
        val col = g.tab[p.col]
        if (p.index !in col.indices || !col[p.index].up) null else {
            val moving = col.subList(p.index, col.size).toList()
            val rest = col.subList(0, p.index).toMutableList()
            if (rest.isNotEmpty() && !rest.last().up) rest[rest.lastIndex] = rest.last().copy(up = true)
            moving to g.copy(tab = g.tab.mapIndexed { i, t -> if (i == p.col) rest else t })
        }
    }
}

@Composable
fun SolitairePage() {
    var game by remember { mutableStateOf(newKlondike()) }
    var pick by remember { mutableStateOf<Pick?>(null) }
    val won = game.found.all { it.size == 13 }

    fun toFound(p: Pick, i: Int): Boolean {
        val (cards, g2) = take(game, p) ?: return false
        if (cards.size != 1 || !canFound(cards[0], game.found[i])) return false
        game = g2.copy(found = g2.found.mapIndexed { k, f -> if (k == i) f + cards[0] else f }); pick = null
        return true
    }
    fun toTab(p: Pick, col: Int): Boolean {
        if (p is Pick.Tab && p.col == col) return false
        val (cards, g2) = take(game, p) ?: return false
        if (!canTab(cards[0], game.tab[col])) return false
        game = g2.copy(tab = g2.tab.mapIndexed { k, t -> if (k == col) t + cards else t }); pick = null
        return true
    }
    /** Aynı karta ikinci dokunuş: uygun temele gönder. */
    fun autoFound(p: Pick): Boolean = (0 until 4).any { toFound(p, it) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF2F5D4A)).padding(6.dp)) {
        val gap = 4.dp
        val cw: Dp = (maxWidth - gap * 6) / 7
        val ch: Dp = cw * 1.4f
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                // deste
                Slot(cw, ch, onClick = {
                    pick = null
                    game = if (game.stock.isNotEmpty()) game.copy(stock = game.stock.dropLast(1), waste = game.waste + game.stock.last().copy(up = true))
                    else game.copy(stock = game.waste.reversed().map { it.copy(up = false) }, waste = emptyList())
                }) { if (game.stock.isNotEmpty()) CardView(PCard(0, 1, false), cw, ch, false) else Text("↻", color = Color.White) }
                // açık kart
                Slot(cw, ch, onClick = {
                    if (game.waste.isNotEmpty()) {
                        if (pick == Pick.Waste) { if (!autoFound(Pick.Waste)) pick = null } else pick = Pick.Waste
                    }
                }) { game.waste.lastOrNull()?.let { CardView(it, cw, ch, pick == Pick.Waste) } }
                Spacer(Modifier.width(cw))
                for (i in 0 until 4) {
                    Slot(cw, ch, onClick = {
                        val p = pick
                        if (p != null) { if (!toFound(p, i)) pick = null }
                        else if (game.found[i].isNotEmpty()) pick = Pick.Found(i)
                    }) {
                        game.found[i].lastOrNull()?.let { CardView(it, cw, ch, pick == Pick.Found(i)) } ?: Text("♠♥♦♣"[i].toString(), color = Color.White.copy(alpha = 0.4f))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (col in 0 until 7) {
                    val pile = game.tab[col]
                    Box(
                        Modifier.width(cw).height(ch + 26.dp * 13).clickable(enabled = pile.isEmpty()) {
                            pick?.let { if (!toTab(it, col)) pick = null }
                        },
                    ) {
                        if (pile.isEmpty()) Box(Modifier.width(cw).height(ch).border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(6.dp)))
                        var y = 0.dp
                        pile.forEachIndexed { idx, c ->
                            val selected = (pick as? Pick.Tab)?.let { it.col == col && idx >= it.index } == true
                            Box(Modifier.offset(y = y).clickable {
                                val p = pick
                                if (p == Pick.Tab(col, idx)) {
                                    val sent = idx == pile.lastIndex && autoFound(p)
                                    if (!sent) pick = null
                                } else if (p != null && toTab(p, col)) {
                                    // taşındı
                                } else if (c.up) {
                                    pick = Pick.Tab(col, idx)
                                } else {
                                    pick = null
                                }
                            }) { CardView(c, cw, ch, selected) }
                            y += if (c.up) 26.dp else 10.dp
                        }
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            if (won) Text("Bitti! 🎉", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text("Bir karta dokun, sonra gideceği yere dokun. Aynı karta iki kez dokunursan üste (temele) gider.", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { game = newKlondike(); pick = null }) { Text("Yeni oyun", color = Color.White) }
        }
    }
}

@Composable
private fun Slot(w: Dp, h: Dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.width(w).height(h).clip(RoundedCornerShape(6.dp)).border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(6.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun CardView(c: PCard, w: Dp, h: Dp, selected: Boolean) {
    Box(
        Modifier.width(w).height(h).clip(RoundedCornerShape(6.dp))
            .background(if (c.up) Color.White else Color(0xFF3D6B8F))
            .border(if (selected) 3.dp else 1.dp, if (selected) Color(0xFFFFC107) else Color(0x55000000), RoundedCornerShape(6.dp)),
    ) {
        if (c.up) Text(
            c.label, color = if (c.red) Color(0xFFC62828) else Color(0xFF212121), fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(3.dp),
        )
    }
}
