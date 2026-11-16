package com.seslikitap.ui.calm

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.container

/** Araba modu: sürüşte rahat dokunulan kocaman düğmeler, ekran açık kalır. */
@Composable
fun CarScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val player = context.container.player
    val st by player.state.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        val w = (context as? Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    BackHandler { onExit() }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0E0E0E)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(st.bookTitle, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(st.chapterTitle, color = Color(0xFFB0B0B0), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onExit, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Default.Close, "Araba modundan çık", tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        BigButton(
            if (st.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            if (st.playing) "Duraklat" else "Oynat",
            Modifier.fillMaxWidth().height(170.dp),
            primary = true,
        ) { player.toggle() }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BigButton(Icons.Default.FastRewind, "30 sn geri", Modifier.height(130.dp)) { player.skipSeconds(-30) }
            BigButton(Icons.Default.FastForward, "30 sn ileri", Modifier.height(130.dp)) { player.skipSeconds(30) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BigButton(Icons.Default.SkipPrevious, "Önceki bölüm", Modifier.height(100.dp)) { player.previousChapter() }
            BigButton(Icons.Default.SkipNext, "Sonraki bölüm", Modifier.height(100.dp)) { player.nextChapter() }
        }
    }
}

@Composable
private fun RowScope.BigButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) =
    BigButtonImpl(icon, label, modifier.weight(1f), false, onClick)

@Composable
private fun BigButton(icon: ImageVector, label: String, modifier: Modifier, primary: Boolean, onClick: () -> Unit) =
    BigButtonImpl(icon, label, modifier, primary, onClick)

@Composable
private fun BigButtonImpl(icon: ImageVector, label: String, modifier: Modifier, primary: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Color(0xFF2E7D6B) else Color(0xFF262626),
            contentColor = Color.White,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(if (primary) 80.dp else 48.dp))
            Text(label, fontSize = if (primary) 24.sp else 20.sp)
        }
    }
}
