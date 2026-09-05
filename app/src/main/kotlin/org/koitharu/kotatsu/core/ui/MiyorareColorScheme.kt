package org.koitharu.kotatsu.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.prefs.VisualEffectPreferences

/**
 * Derives a stable Material 3 palette from one accent color. The calculation is finite and intended
 * to be remembered by the caller; no shader, animation or per-list-item work is involved.
 */
fun miyorareColorScheme(
	preset: MiyorareThemePreset,
	customAccent: String,
	darkTheme: Boolean,
	effectLevel: VisualEffectLevel,
): ColorScheme {
	val requestedArgb = if (preset == MiyorareThemePreset.CUSTOM) {
		VisualEffectPreferences.parseAccentArgb(customAccent) ?: MiyorareThemePreset.MIYORARE.accentArgb
	} else {
		preset.accentArgb
	}
	val primary = makeAccentReadable(Color(requestedArgb), darkTheme)
	val secondary = makeAccentReadable(lerp(primary, Color(0xFF8A6CFF), 0.36f), darkTheme)
	val tertiary = makeAccentReadable(lerp(primary, Color(0xFF35C5D7), 0.48f), darkTheme)
	val tint = effectLevel.surfaceTintFraction.coerceIn(0f, 0.24f)

	return if (darkTheme) {
		val background = lerp(Color(0xFF070A14), primary, tint * 0.18f)
		val surface = lerp(Color(0xFF0D1120), primary, tint * 0.24f)
		val surfaceVariant = lerp(Color(0xFF1F2740), primary, tint * 0.34f)
		val primaryContainer = lerp(Color(0xFF1A2340), primary, 0.34f + tint * 0.30f)
		val secondaryContainer = lerp(Color(0xFF241D3B), secondary, 0.32f + tint * 0.24f)
		darkColorScheme(
			primary = primary,
			onPrimary = contentColorFor(primary),
			primaryContainer = primaryContainer,
			onPrimaryContainer = contentColorFor(primaryContainer),
			secondary = secondary,
			onSecondary = contentColorFor(secondary),
			secondaryContainer = secondaryContainer,
			onSecondaryContainer = contentColorFor(secondaryContainer),
			tertiary = tertiary,
			onTertiary = contentColorFor(tertiary),
			background = background,
			onBackground = Color(0xFFF4F6FF),
			surface = surface,
			onSurface = Color(0xFFF2F4FF),
			surfaceVariant = surfaceVariant,
			onSurfaceVariant = Color(0xFFB8BDD0),
			outline = lerp(Color(0xFF737A91), primary, tint * 0.45f),
		)
	} else {
		val background = lerp(Color(0xFFF7F8FC), primary, tint * 0.10f)
		val surface = lerp(Color.White, primary, tint * 0.08f)
		val surfaceVariant = lerp(Color(0xFFE4E8F5), primary, tint * 0.20f)
		val primaryContainer = lerp(Color(0xFFE8EBF8), primary, 0.14f + tint * 0.28f)
		val secondaryContainer = lerp(Color(0xFFF0EAF8), secondary, 0.12f + tint * 0.24f)
		lightColorScheme(
			primary = primary,
			onPrimary = contentColorFor(primary),
			primaryContainer = primaryContainer,
			onPrimaryContainer = contentColorFor(primaryContainer),
			secondary = secondary,
			onSecondary = contentColorFor(secondary),
			secondaryContainer = secondaryContainer,
			onSecondaryContainer = contentColorFor(secondaryContainer),
			tertiary = tertiary,
			onTertiary = contentColorFor(tertiary),
			background = background,
			onBackground = Color(0xFF111425),
			surface = surface,
			onSurface = Color(0xFF15182A),
			surfaceVariant = surfaceVariant,
			onSurfaceVariant = Color(0xFF5D6278),
			outline = lerp(Color(0xFF7A819A), primary, tint * 0.30f),
		)
	}
}

private fun makeAccentReadable(accent: Color, darkTheme: Boolean): Color {
	val luminance = accent.luminance()
	return when {
		darkTheme && luminance < 0.24f -> lerp(accent, Color.White, 0.42f)
		!darkTheme && luminance > 0.66f -> lerp(accent, Color.Black, 0.28f)
		else -> accent
	}
}

private fun contentColorFor(background: Color): Color {
	return if (background.luminance() > 0.43f) Color(0xFF0A1020) else Color.White
}
