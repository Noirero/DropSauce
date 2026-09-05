package org.koitharu.kotatsu.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel

/** Reusable semantic colors for Modern components; screens never derive their own palette. */
data class MiyorareVisualPalette(
	val primary: Color,
	val secondary: Color,
	val accent: Color,
	val selectedSurface: Color,
	val border: Color,
	val glow: Color,
	val chip: Color,
	val button: Color,
	val onButton: Color,
)

data class MiyorareThemeColors(
	val colorScheme: ColorScheme,
	val visualPalette: MiyorareVisualPalette,
)

val LocalMiyorareVisualPalette = staticCompositionLocalOf {
	MiyorareVisualPalette(
		primary = Color.Unspecified,
		secondary = Color.Unspecified,
		accent = Color.Unspecified,
		selectedSurface = Color.Unspecified,
		border = Color.Unspecified,
		glow = Color.Transparent,
		chip = Color.Unspecified,
		button = Color.Unspecified,
		onButton = Color.Unspecified,
	)
}

/**
 * Derives one stable Material palette and semantic token set from a single accent color.
 * This is finite CPU work remembered by the theme caller: no shader, animation or per-item work.
 */
fun miyorareThemeColors(
	preset: MiyorareThemePreset,
	customAccent: String,
	darkTheme: Boolean,
	amoled: Boolean,
	effectLevel: VisualEffectLevel,
): MiyorareThemeColors {
	val requestedArgb = if (preset == MiyorareThemePreset.CUSTOM) {
		MiyorareAppearance.parseAccentArgb(customAccent) ?: MiyorareThemePreset.MIYORARE.accentArgb
	} else {
		preset.accentArgb
	}
	val useAmoled = darkTheme && amoled
	val baseSurface = when {
		useAmoled -> Color.Black
		darkTheme -> Color(0xFF0D1120)
		else -> Color.White
	}
	val primary = ensureVisibleAgainst(Color(requestedArgb), baseSurface, darkTheme)
	val secondary = ensureVisibleAgainst(lerp(primary, Color(0xFF8A6CFF), 0.36f), baseSurface, darkTheme)
	val accent = ensureVisibleAgainst(lerp(primary, Color(0xFF35C5D7), 0.48f), baseSurface, darkTheme)
	val tint = effectLevel.surfaceTintFraction.coerceIn(0f, 0.24f)

	val colorScheme: ColorScheme
	val selectedSurface: Color
	val border: Color
	val chip: Color
	if (darkTheme) {
		val background = if (useAmoled) Color.Black else lerp(Color(0xFF070A14), primary, tint * 0.18f)
		val surface = if (useAmoled) Color.Black else lerp(baseSurface, primary, tint * 0.24f)
		val surfaceVariant = lerp(Color(0xFF1F2740), primary, tint * 0.34f)
		selectedSurface = lerp(Color(0xFF1A2340), primary, 0.34f + tint * 0.30f)
		chip = lerp(Color(0xFF241D3B), secondary, 0.32f + tint * 0.24f)
		border = lerp(Color(0xFF737A91), primary, tint * 0.45f)
		colorScheme = darkColorScheme(
			primary = primary,
			onPrimary = bestContentColor(primary),
			primaryContainer = selectedSurface,
			onPrimaryContainer = bestContentColor(selectedSurface),
			secondary = secondary,
			onSecondary = bestContentColor(secondary),
			secondaryContainer = chip,
			onSecondaryContainer = bestContentColor(chip),
			tertiary = accent,
			onTertiary = bestContentColor(accent),
			background = background,
			onBackground = Color(0xFFF4F6FF),
			surface = surface,
			onSurface = Color(0xFFF2F4FF),
			surfaceVariant = surfaceVariant,
			onSurfaceVariant = Color(0xFFB8BDD0),
			outline = border,
			outlineVariant = lerp(Color(0xFF343B51), primary, tint * 0.22f),
			surfaceContainer = lerp(Color(0xFF12182A), primary, tint * 0.20f),
			surfaceContainerHigh = lerp(Color(0xFF192137), primary, tint * 0.26f),
		)
	} else {
		val background = lerp(Color(0xFFF7F8FC), primary, tint * 0.10f)
		val surface = lerp(baseSurface, primary, tint * 0.08f)
		val surfaceVariant = lerp(Color(0xFFE4E8F5), primary, tint * 0.20f)
		selectedSurface = lerp(Color(0xFFE8EBF8), primary, 0.14f + tint * 0.28f)
		chip = lerp(Color(0xFFF0EAF8), secondary, 0.12f + tint * 0.24f)
		border = lerp(Color(0xFF7A819A), primary, tint * 0.30f)
		colorScheme = lightColorScheme(
			primary = primary,
			onPrimary = bestContentColor(primary),
			primaryContainer = selectedSurface,
			onPrimaryContainer = bestContentColor(selectedSurface),
			secondary = secondary,
			onSecondary = bestContentColor(secondary),
			secondaryContainer = chip,
			onSecondaryContainer = bestContentColor(chip),
			tertiary = accent,
			onTertiary = bestContentColor(accent),
			background = background,
			onBackground = Color(0xFF111425),
			surface = surface,
			onSurface = Color(0xFF15182A),
			surfaceVariant = surfaceVariant,
			onSurfaceVariant = Color(0xFF5D6278),
			outline = border,
			outlineVariant = lerp(Color(0xFFD4D8E7), primary, tint * 0.18f),
			surfaceContainer = lerp(Color(0xFFF0F2FA), primary, tint * 0.14f),
			surfaceContainerHigh = lerp(Color(0xFFE8EBF7), primary, tint * 0.20f),
		)
	}

	val glowAlpha = when (effectLevel) {
		VisualEffectLevel.LIGHT -> 0f
		VisualEffectLevel.BALANCED -> 0.22f
		VisualEffectLevel.FULL -> 0.34f
	}
	return MiyorareThemeColors(
		colorScheme = colorScheme,
		visualPalette = MiyorareVisualPalette(
			primary = primary,
			secondary = secondary,
			accent = accent,
			selectedSurface = selectedSurface,
			border = border,
			glow = primary.copy(alpha = glowAlpha),
			chip = chip,
			button = primary,
			onButton = bestContentColor(primary),
		),
	)
}

fun classicMiyorareVisualPalette(
	colorScheme: ColorScheme,
	effectLevel: VisualEffectLevel,
): MiyorareVisualPalette = MiyorareVisualPalette(
	primary = colorScheme.primary,
	secondary = colorScheme.secondary,
	accent = colorScheme.tertiary,
	selectedSurface = colorScheme.primaryContainer,
	border = colorScheme.outline,
	glow = colorScheme.primary.copy(alpha = if (effectLevel == VisualEffectLevel.FULL) 0.20f else 0f),
	chip = colorScheme.secondaryContainer,
	button = colorScheme.primary,
	onButton = colorScheme.onPrimary,
)

private fun ensureVisibleAgainst(accent: Color, surface: Color, darkTheme: Boolean): Color {
	var candidate = accent
	val target = if (darkTheme) Color.White else Color.Black
	repeat(8) {
		if (contrastRatio(candidate, surface) >= 3f) return candidate
		candidate = lerp(candidate, target, 0.16f)
	}
	return candidate
}

private fun bestContentColor(background: Color): Color {
	val dark = Color(0xFF0A1020)
	return if (contrastRatio(Color.White, background) >= contrastRatio(dark, background)) Color.White else dark
}

private fun contrastRatio(first: Color, second: Color): Float {
	val lighter = maxOf(first.luminance(), second.luminance())
	val darker = minOf(first.luminance(), second.luminance())
	return (lighter + 0.05f) / (darker + 0.05f)
}
