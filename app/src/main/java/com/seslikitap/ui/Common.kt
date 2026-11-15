package com.seslikitap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.seslikitap.AppContainer
import com.seslikitap.container
import java.io.File

/** AppContainer'a erişen ViewModel oluşturur. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline factory: (AppContainer) -> VM,
): VM {
    val container = LocalContext.current.container
    return viewModel(
        key = key,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = factory(container) as T
        },
    )
}

private val PLACEHOLDER_COLORS = listOf(
    Color(0xFF1F4E5F), Color(0xFF6B3E26), Color(0xFF3D5A40), Color(0xFF5B3A6B),
    Color(0xFF7A2E3A), Color(0xFF2E4A7A), Color(0xFF6B5B2E), Color(0xFF34495E),
)

/** Kapak resmi; yoksa başlıktan renkli bir kapak çizer. */
@Composable
fun BookCover(
    coverPath: String?,
    title: String,
    author: String,
    modifier: Modifier = Modifier,
) {
    if (coverPath != null && File(coverPath).exists()) {
        AsyncImage(
            model = File(coverPath),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        val color = PLACEHOLDER_COLORS[(title.hashCode() and 0x7fffffff) % PLACEHOLDER_COLORS.size]
        Box(modifier.background(color), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = title + if (author.isNotBlank()) "\n\n$author" else "",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 7,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale("tr"), "%.1f MB", bytes / 1024.0 / 1024.0)
}
