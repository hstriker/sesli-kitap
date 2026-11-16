package com.seslikitap.ui.calm

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.container
import com.seslikitap.player.Battery
import com.seslikitap.player.FocusMode
import com.seslikitap.player.PlaybackService
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sakin ekran: ekran kararır, yavaşça hareket eden bir görüntü kalır. Ekrana bakmadan kumanda:
 * çift dokun = durdur/devam, sola/sağa kaydır = 30 sn geri/ileri, uzun bas = çık.
 * [ritual]: başlangıç ritüeli — Rahatsız Etmeyin, üç derin nefes, sonra kitap yumuşakça başlar.
 */
@Composable
fun CalmScreen(ritual: Boolean, onExit: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val player = container.player
    val prefs = container.prefs
    val st by player.state.collectAsStateWithLifecycle()
    val chapter by player.chapter.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val saving = remember { Battery.saving(context, prefs) }

    var scene by remember { mutableStateOf(prefs.calmScene) }
    var showText by remember { mutableStateOf(prefs.calmShowText) }
    var hint by remember { mutableStateOf(true) }
    var flash by remember { mutableStateOf<String?>(null) }
    var breathing by remember { mutableStateOf(ritual) }

    // Ekranı karart; pil dostu modda ekranın kendiliğinden kapanmasına izin ver
    DisposableEffect(Unit) {
        val w = context.findActivity()?.window
        val old = w?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        w?.let {
            val a = it.attributes
            a.screenBrightness = 0.03f
            it.attributes = a
            if (!saving) it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            w?.let {
                val a = it.attributes
                a.screenBrightness = old
                it.attributes = a
                it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
    LaunchedEffect(ritual) { if (ritual) FocusMode.enter(context, prefs) }
    BackHandler { onExit() }
    LaunchedEffect(hint) { if (hint) { delay(6000); hint = false } }
    LaunchedEffect(flash) { if (flash != null) { delay(1200); flash = null } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val wasPlaying = player.state.value.playing
                        if (wasPlaying) player.pause() else { player.play(); PlaybackService.start(context) }
                        flash = if (wasPlaying) "Durdu" else "Devam"
                    },
                    onLongPress = { onExit() },
                    onTap = { hint = true },
                )
            }
            .pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        if (dx > 150f) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            player.skipSeconds(30); flash = "30 sn ileri"
                        } else if (dx < -150f) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            player.skipSeconds(-30); flash = "30 sn geri"
                        }
                    },
                    onHorizontalDrag = { _, d -> dx += d },
                )
            },
    ) {
        Scene(scene, animate = !saving)

        Column(Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 24.dp, end = 24.dp)) {
            Text(st.bookTitle, color = Color.White.copy(alpha = 0.22f), fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(st.chapterTitle, color = Color.White.copy(alpha = 0.15f), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        if (showText && !breathing) {
            val ch = chapter
            if (ch != null && st.sentenceIndex in ch.sentences.indices) {
                Text(
                    ch.text(st.sentenceIndex),
                    color = Color.White.copy(alpha = 0.33f),
                    fontSize = 22.sp,
                    lineHeight = 32.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
        }

        flash?.let {
            Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 28.sp, modifier = Modifier.align(Alignment.Center))
        }

        if (hint && !breathing) {
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Çift dokun: durdur / devam  ·  Kaydır: 30 sn  ·  Uzun bas: çık",
                    color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp, textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 14.dp)) {
                    listOf("yagmur" to "Yağmur", "mum" to "Mum", "yildiz" to "Yıldızlar").forEach { (k, label) ->
                        Text(
                            label,
                            color = Color.White.copy(alpha = if (scene == k) 0.7f else 0.3f),
                            fontSize = 15.sp,
                            modifier = Modifier.clickable { scene = k; prefs.calmScene = k; hint = true },
                        )
                    }
                    Text(
                        if (showText) "Yazıyı gizle" else "Yazıyı göster",
                        color = Color.White.copy(alpha = 0.3f),
                        fontSize = 15.sp,
                        modifier = Modifier.clickable { showText = !showText; prefs.calmShowText = showText; hint = true },
                    )
                }
                if (saving) {
                    Text("Pil dostu mod: görüntü durağan", color = Color.White.copy(alpha = 0.25f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        if (breathing) {
            BreathingOverlay {
                breathing = false
                if (!player.state.value.playing) { player.play(); PlaybackService.start(context) }
            }
        }
    }
}

/** Ritüel: üç derin nefes (4 sn al, 1,5 sn tut, 6 sn ver). */
@Composable
private fun BreathingOverlay(onDone: () -> Unit) {
    val scale = remember { Animatable(0.35f) }
    var label by remember { mutableStateOf("Rahatla…") }
    LaunchedEffect(Unit) {
        delay(1800)
        repeat(3) {
            label = "Nefes al…"
            scale.animateTo(1f, tween(4000, easing = FastOutSlowInEasing))
            label = "Tut…"
            delay(1500)
            label = "Yavaşça ver…"
            scale.animateTo(0.35f, tween(6000, easing = FastOutSlowInEasing))
        }
        label = "Hoş geldin."
        delay(1500)
        onDone()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(280.dp)) {
            val r = size.minDimension / 2 * scale.value
            drawCircle(
                Brush.radialGradient(listOf(Color(0x558FB8C9), Color(0x108FB8C9)), center = center, radius = r.coerceAtLeast(1f)),
                radius = r,
            )
            drawCircle(Color(0x668FB8C9), radius = r, style = Stroke(width = 2.dp.toPx()))
        }
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 22.sp)
    }
}

/** Yavaş arka plan görüntüsü: yağmur, mum ya da yıldızlar. */
@Composable
private fun Scene(kind: String, animate: Boolean) {
    val t = if (animate) {
        val inf = rememberInfiniteTransition(label = "sahne")
        inf.animateFloat(0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "zaman").value
    } else 0.3f
    val dots = remember { List(60) { i -> Random(i * 7919).let { r -> floatArrayOf(r.nextFloat(), r.nextFloat(), 0.4f + r.nextFloat() * 0.6f, r.nextFloat()) } } }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        when (kind) {
            "mum" -> {
                val flicker = (sin(2 * PI * t * 90).toFloat() * 0.5f + sin(2 * PI * t * 37).toFloat() * 0.5f) * 0.06f
                val cx = w / 2
                val cy = h * 0.68f
                drawCircle(
                    Brush.radialGradient(listOf(Color(0x40FFB060), Color.Transparent), center = Offset(cx, cy - 40), radius = w * (0.55f + flicker)),
                    radius = w * (0.55f + flicker), center = Offset(cx, cy - 40),
                )
                // mum gövdesi
                drawRect(Color(0x30FFF3E0), topLeft = Offset(cx - 22, cy), size = androidx.compose.ui.geometry.Size(44f, h * 0.2f))
                // alev
                val fh = 60f * (1f + flicker * 2)
                drawOval(Color(0x90FFC070), topLeft = Offset(cx - 13, cy - fh - 6), size = androidx.compose.ui.geometry.Size(26f, fh))
                drawOval(Color(0x70FFFFFF), topLeft = Offset(cx - 5, cy - fh * 0.55f - 6), size = androidx.compose.ui.geometry.Size(10f, fh * 0.5f))
            }
            "yildiz" -> {
                dots.forEach { d ->
                    val a = 0.15f + 0.25f * (0.5f + 0.5f * sin(2 * PI * (t * 6 * d[2] + d[3])).toFloat())
                    drawCircle(Color.White.copy(alpha = a), radius = 1.2f + d[2] * 2.2f, center = Offset(d[0] * w, d[1] * h))
                }
            }
            else -> {
                dots.forEach { d ->
                    val y = ((d[1] + t * 8 * d[2]) % 1f) * (h + 120) - 60
                    val x = d[0] * w
                    drawLine(
                        Color(0xFF9FC3D6).copy(alpha = 0.10f + 0.12f * d[2]),
                        start = Offset(x, y), end = Offset(x - 4, y + 26 + 30 * d[2]),
                        strokeWidth = 1.5f,
                    )
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
