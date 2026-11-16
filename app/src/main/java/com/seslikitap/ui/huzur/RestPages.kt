@file:OptIn(ExperimentalLayoutApi::class)

package com.seslikitap.ui.huzur

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.seslikitap.player.AudioSession
import com.seslikitap.player.CalmAi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ Uyku hikâyesi

private val SLEEP_TOPICS = listOf(
    "Deniz kenarında bir akşam yürüyüşü", "Karlı bir dağ evinde şömine başı", "Yağmurlu bir gecede eski bir kütüphane",
    "Sonbahar ormanında yavaş bir yürüyüş", "Ay ışığında sakin bir göl", "Gece giden bir trende yolculuk",
    "Ege'de bir zeytinlikte gün batımı", "Bulutların üstünde süzülen bir balon",
)

@Composable
fun SleepStoryPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var topic by remember { mutableStateOf(SLEEP_TOPICS.first()) }
    var custom by remember { mutableStateOf("") }
    var text by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fade by remember { mutableStateOf(true) }

    fun make(fresh: Boolean) {
        val t = custom.trim().ifBlank { topic }
        busy = true; error = null
        scope.launch {
            try {
                val story = CalmAi.ask(context, "uyku|$t", CalmAi.sleepStory(t), 0.9, fresh)
                text = story
                AudioSession.play(context, "Uyku hikâyesi", story) { if (fade) AudioSession.stop() }
            } catch (e: Exception) {
                error = e.message ?: "Hikâye alınamadı"
            } finally { busy = false }
        }
    }

    PageColumn {
        Text("Bir konu seç ya da kendin yaz. Hikâye sana özel yazılır ve sakin bir sesle okunur.", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            SLEEP_TOPICS.forEach { t -> FilterChip(selected = topic == t && custom.isBlank(), onClick = { topic = t; custom = "" }, label = { Text(t) }) }
        }
        OutlinedTextField(value = custom, onValueChange = { custom = it }, placeholder = { Text("ya da kendi konun…") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = fade, onCheckedChange = { fade = it })
            Text("Bitince sessizce kapan")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { make(false) }, enabled = !busy) { Text("Hikâyeyi dinle") }
            if (text != null) OutlinedButton(onClick = { make(true) }, enabled = !busy) { Text("Yeni bir hikâye") }
        }
        if (busy) CircularProgressIndicator(Modifier.padding(16.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        text?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp)) }
    }
}

// ------------------------------------------------------------------ Nefes

private data class Pattern(val name: String, val inhale: Int, val hold: Int, val exhale: Int, val hold2: Int)

private val PATTERNS = listOf(
    Pattern("4-7-8 (uykuya hazırlık)", 4, 7, 8, 0),
    Pattern("Kutu nefesi (4-4-4-4)", 4, 4, 4, 4),
    Pattern("Sakin nefes (4-6)", 4, 0, 6, 0),
)

@Composable
fun BreathPage() {
    var mode by remember { mutableStateOf("desen") }
    PageColumn {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == "desen", onClick = { mode = "desen" }, label = { Text("Nefes egzersizi") })
            FilterChip(selected = mode == "balon", onClick = { mode = "balon" }, label = { Text("Nefes balonu") })
            FilterChip(selected = mode == "beden", onClick = { mode = "beden" }, label = { Text("Beden taraması") })
        }
        when (mode) {
            "desen" -> BreathPattern()
            "balon" -> BreathBalloon()
            else -> BodyScan()
        }
    }
}

@Composable
private fun BreathPattern() {
    val context = LocalContext.current
    var pattern by remember { mutableStateOf(PATTERNS.first()) }
    var rounds by remember { mutableIntStateOf(4) }
    var voice by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("Hazır olduğunda başla") }
    val scale = remember { Animatable(0.35f) }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        AudioSession.stop()
        repeat(rounds) { r ->
            label = "Nefes al… (${r + 1}/$rounds)"; if (voice) AudioSession.say(context, "Nefes al")
            scale.animateTo(1f, tween(pattern.inhale * 1000, easing = FastOutSlowInEasing))
            if (pattern.hold > 0) { label = "Tut…"; if (voice) AudioSession.say(context, "Tut"); delay(pattern.hold * 1000L) }
            label = "Yavaşça ver…"; if (voice) AudioSession.say(context, "Ver")
            scale.animateTo(0.35f, tween(pattern.exhale * 1000, easing = FastOutSlowInEasing))
            if (pattern.hold2 > 0) { label = "Bekle…"; delay(pattern.hold2 * 1000L) }
        }
        label = "Güzel. Bir süre olduğu gibi kal."
        if (voice) AudioSession.say(context, "Güzel. Bir süre olduğu gibi kal.")
        running = false
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            PATTERNS.forEach { p -> FilterChip(selected = pattern == p, onClick = { if (!running) pattern = p }, label = { Text(p.name) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(4, 8, 12).forEach { n -> FilterChip(selected = rounds == n, onClick = { if (!running) rounds = n }, label = { Text("$n tur") }, modifier = Modifier.padding(end = 6.dp)) }
            Checkbox(checked = voice, onCheckedChange = { voice = it })
            Text("Sesli")
        }
        BreathCircle(scale.value, label)
        if (running) OutlinedButton(onClick = { running = false }) { Text("Bitir") }
        else Button(onClick = { running = true }) { Text("Başla") }
    }
}

@Composable
private fun BreathCircle(scale: Float, label: String) {
    Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(280.dp)) {
            val r = size.minDimension / 2 * scale
            drawCircle(Brush.radialGradient(listOf(Color(0x668FB8C9), Color(0x148FB8C9)), center = center, radius = r.coerceAtLeast(1f)), radius = r)
            drawCircle(Color(0x998FB8C9), radius = r, style = Stroke(width = 2.dp.toPx()))
        }
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/** Nefes balonu: basılı tuttukça nefes al (balon şişer), bırakınca yavaşça ver (balon iner). */
@Composable
private fun BreathBalloon() {
    val scope = rememberCoroutineScope()
    val balloon = remember { Animatable(0.25f) }
    var breaths by remember { mutableIntStateOf(0) }
    var label by remember { mutableStateOf("Parmağını basılı tut ve nefes al") }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Balon, nefesinle birlikte şişip iner. Acele yok.", style = MaterialTheme.typography.bodySmall)
        Box(
            Modifier.size(320.dp).pointerInput(Unit) {
                detectTapGestures(onPress = {
                    label = "Nefes al…"
                    val grow = scope.launch { balloon.animateTo(1f, tween(((1f - balloon.value) * 6000).toInt().coerceAtLeast(1), easing = LinearEasing)) }
                    tryAwaitRelease()
                    grow.cancel()
                    label = "Yavaşça ver…"
                    scope.launch {
                        balloon.animateTo(0.25f, tween(((balloon.value - 0.25f) * 8000).toInt().coerceAtLeast(1), easing = LinearEasing))
                        breaths++
                        label = if (breaths % 5 == 0) "$breaths nefes. Çok güzel 🌿" else "Hazır olduğunda yeniden"
                    }
                })
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(300.dp)) {
                val r = this.size.minDimension / 2 * balloon.value
                val c = Offset(center.x, center.y - 10)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFF3B6C4), Color(0xFFE38AA0)), center = Offset(c.x - r / 3, c.y - r / 3), radius = r * 1.4f), radius = r, center = c)
                drawLine(Color(0x88555555), start = Offset(c.x, c.y + r), end = Offset(c.x, c.y + r + 60), strokeWidth = 2f)
            }
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.BottomCenter))
        }
        Text("$breaths nefes", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun BodyScan() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(top = 8.dp)) {
        Text("Yatarak dinle. Bedeninin her bölgesine sırayla, nazikçe dikkat verirsin (5-6 dakika).", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val t = CalmAi.ask(context, "beden-taramasi", CalmAi.bodyScan(), 0.6)
                        text = t
                        AudioSession.play(context, "Beden taraması", t)
                    } catch (e: Exception) { error = e.message } finally { busy = false }
                }
            }) { Text("Başla") }
            if (text != null) TextButton(onClick = {
                busy = true
                scope.launch {
                    try {
                        val t = CalmAi.ask(context, "beden-taramasi", CalmAi.bodyScan(), 0.8, fresh = true)
                        text = t
                        AudioSession.play(context, "Beden taraması", t)
                    } catch (e: Exception) { error = e.message } finally { busy = false }
                }
            }) { Text("Farklı bir metin") }
        }
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
    }
}
