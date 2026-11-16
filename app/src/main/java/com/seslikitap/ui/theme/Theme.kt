package com.seslikitap.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow

private val Light = lightColorScheme(
    primary = Color(0xFF1F4E5F),
    secondary = Color(0xFFB7791F),
    tertiary = Color(0xFF4C6A3D),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8CCFE0),
    secondary = Color(0xFFF2B134),
    tertiary = Color(0xFFA9CF92),
)

/** Uygulama temaları: "sistem" (telefonun renkleri) ya da orman, deniz, gece, sonbahar. */
object AppTheme {
    val NAMES = listOf("sistem" to "Telefonun renkleri", "orman" to "Orman", "deniz" to "Deniz", "gece" to "Gece", "sonbahar" to "Sonbahar")
    val current = MutableStateFlow("sistem")

    fun load(context: Context) {
        current.value = context.getSharedPreferences(com.seslikitap.Prefs.FILE, Context.MODE_PRIVATE).getString("theme", "sistem") ?: "sistem"
    }

    fun set(context: Context, name: String) {
        context.getSharedPreferences(com.seslikitap.Prefs.FILE, Context.MODE_PRIVATE).edit().putString("theme", name).apply()
        current.value = name
    }

    fun scheme(name: String, dark: Boolean): ColorScheme? = when (name) {
        "orman" -> if (dark) darkColorScheme(
            primary = Color(0xFF9CD3A3), secondary = Color(0xFFD9C38C), tertiary = Color(0xFFB5D99C),
            background = Color(0xFF111A13), surface = Color(0xFF111A13), surfaceVariant = Color(0xFF26352A), primaryContainer = Color(0xFF2E4A34),
        ) else lightColorScheme(
            primary = Color(0xFF2F6B3B), secondary = Color(0xFF8A6D2B), tertiary = Color(0xFF55703A),
            background = Color(0xFFF4F7F0), surface = Color(0xFFF4F7F0), surfaceVariant = Color(0xFFE1EADB), primaryContainer = Color(0xFFC6E6CB),
        )
        "deniz" -> if (dark) darkColorScheme(
            primary = Color(0xFF8ED0F2), secondary = Color(0xFF9FE0D6), tertiary = Color(0xFFB9C8F5),
            background = Color(0xFF0D1720), surface = Color(0xFF0D1720), surfaceVariant = Color(0xFF1F3240), primaryContainer = Color(0xFF1D4560),
        ) else lightColorScheme(
            primary = Color(0xFF1A5F86), secondary = Color(0xFF2B8C83), tertiary = Color(0xFF4F63A8),
            background = Color(0xFFF2F8FB), surface = Color(0xFFF2F8FB), surfaceVariant = Color(0xFFDCEBF3), primaryContainer = Color(0xFFC3E3F5),
        )
        "gece" -> darkColorScheme(
            primary = Color(0xFFB8B2F2), secondary = Color(0xFFF2D58C), tertiary = Color(0xFF9EC9F0),
            background = Color(0xFF0B0B16), surface = Color(0xFF0B0B16), surfaceVariant = Color(0xFF1E1D33), primaryContainer = Color(0xFF2E2B55),
        )
        "sonbahar" -> if (dark) darkColorScheme(
            primary = Color(0xFFF0B27A), secondary = Color(0xFFE5C07B), tertiary = Color(0xFFD9967A),
            background = Color(0xFF1A120D), surface = Color(0xFF1A120D), surfaceVariant = Color(0xFF3A2A20), primaryContainer = Color(0xFF5A3620),
        ) else lightColorScheme(
            primary = Color(0xFF9A4A1C), secondary = Color(0xFF8C6A1E), tertiary = Color(0xFF8E3B2E),
            background = Color(0xFFFBF5EE), surface = Color(0xFFFBF5EE), surfaceVariant = Color(0xFFF1E2D3), primaryContainer = Color(0xFFF6D3B8),
        )
        else -> null
    }
}

@Composable
fun SesliKitapTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val name by AppTheme.current.collectAsStateWithLifecycle()
    val scheme = AppTheme.scheme(name, dark) ?: when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
