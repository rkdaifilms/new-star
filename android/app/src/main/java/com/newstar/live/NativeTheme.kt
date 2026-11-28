package com.newstar.live

import android.graphics.Color as AndroidColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalContext

private data class NativePalette(
    val background: String,
    val surface: String,
    val foreground: String,
    val muted: String,
    val accent: String
)

private val nativePalettes = mapOf(
    "dark" to NativePalette("#080808", "#171717", "#ffffff", "#aaaaaa", "#fe2c55"),
    "light" to NativePalette("#f4f6fa", "#ffffff", "#171923", "#5d6472", "#d92350"),
    "ocean" to NativePalette("#071923", "#102b3a", "#f4fbff", "#a8c1cc", "#25b8d0"),
    "forest" to NativePalette("#101b14", "#1b2b20", "#f4fff6", "#afc5b3", "#39a96b"),
    "sunset" to NativePalette("#21130e", "#3b2118", "#fff4ed", "#d9a88d", "#ff874d"),
    "purple" to NativePalette("#160e28", "#281943", "#f7f0ff", "#c2addb", "#b58cff"),
    "rose" to NativePalette("#260f1b", "#3b1b2b", "#fff1f6", "#d9a9bc", "#ff6f9e"),
    "crimson" to NativePalette("#210b10", "#39131d", "#fff1f3", "#d7a1aa", "#ff405d"),
    "gold" to NativePalette("#211b0b", "#382d12", "#fff8df", "#d5c28a", "#e9b949"),
    "teal" to NativePalette("#08201e", "#103532", "#eafffb", "#a1cbc4", "#26c6b5"),
    "cyan" to NativePalette("#071c27", "#103344", "#edfbff", "#a6cbd8", "#48d5f5"),
    "indigo" to NativePalette("#10132c", "#1b2250", "#f0f2ff", "#aeb6df", "#7d8cff"),
    "lavender" to NativePalette("#1d1730", "#30264a", "#f8f2ff", "#c8b8df", "#c2a0ff"),
    "mint" to NativePalette("#0d211b", "#18372c", "#edfff6", "#add2bf", "#62d6a2"),
    "emerald" to NativePalette("#071d16", "#103526", "#edfff6", "#a3cbb7", "#28c985"),
    "ruby" to NativePalette("#220c1c", "#3b1530", "#fff0fa", "#d7a5c6", "#f34e9b"),
    "amber" to NativePalette("#211608", "#3b2810", "#fff6e8", "#d6bd91", "#ffb02e"),
    "coral" to NativePalette("#24120f", "#3c211c", "#fff3ef", "#d9aaa0", "#ff806e"),
    "slate" to NativePalette("#111820", "#202b36", "#f3f6fa", "#aebbc8", "#8ba8c4"),
    "coffee" to NativePalette("#1b120d", "#302117", "#fff5ec", "#c9ae98", "#c58b5a"),
    "plum" to NativePalette("#1e1021", "#351d39", "#fff1ff", "#c7a9cb", "#c17acb"),
    "sakura" to NativePalette("#27151d", "#432632", "#fff3f7", "#d9b2c0", "#ff91b8"),
    "neon" to NativePalette("#080d16", "#121d2b", "#f4ffff", "#a5c2c7", "#4dffdb"),
    "sand" to NativePalette("#211d14", "#383124", "#fff9e9", "#d1c4a4", "#d8a85d"),
    "aurora" to NativePalette("#0c1720", "#192b38", "#f2ffff", "#a8c7d0", "#7ee0c3")
)

private fun nativeColor(value: String): Color = Color(AndroidColor.parseColor(value))

private fun safeNativeTextScale(value: String?): Float {
    val parsed = value?.toFloatOrNull()
    return if (parsed != null && parsed.isFinite() && parsed > 0f) {
        parsed.coerceIn(0.9f, 1.25f)
    } else {
        1f
    }
}

@Composable
fun NewStarNativeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("newstar_preferences", android.content.Context.MODE_PRIVATE)
    }
    var selected by remember { mutableStateOf(prefs.getString("theme", "dark") ?: "dark") }
    var textScale by remember { mutableStateOf(safeNativeTextScale(prefs.getString("textScale", "1"))) }
    var highContrast by remember { mutableStateOf(prefs.getBoolean("highContrast", false)) }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "theme") selected = prefs.getString("theme", "dark") ?: "dark"
            if (key == "textScale") textScale = safeNativeTextScale(prefs.getString("textScale", "1"))
            if (key == "highContrast") highContrast = prefs.getBoolean("highContrast", false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val basePalette = nativePalettes[selected] ?: nativePalettes.getValue("dark")
    val palette = if (highContrast) {
        if (selected == "light") basePalette.copy(foreground = "#000000", muted = "#222222", accent = "#000000")
        else basePalette.copy(background = "#000000", foreground = "#ffffff", muted = "#ffffff", accent = "#ffffff")
    } else basePalette
    val colors = if (selected == "light") {
        lightColorScheme(
            primary = nativeColor(palette.accent),
            secondary = nativeColor(palette.accent),
            background = nativeColor(palette.background),
            surface = nativeColor(palette.surface),
            onBackground = nativeColor(palette.foreground),
            onSurface = nativeColor(palette.foreground),
            onPrimary = Color.White,
            onSecondary = Color.White
        )
    } else {
        darkColorScheme(
            primary = nativeColor(palette.accent),
            secondary = nativeColor(palette.accent),
            background = nativeColor(palette.background),
            surface = nativeColor(palette.surface),
            onBackground = nativeColor(palette.foreground),
            onSurface = nativeColor(palette.foreground),
            onPrimary = Color.Black,
            onSecondary = Color.Black
        )
    }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, safeNativeTextScale(textScale.toString()))) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
