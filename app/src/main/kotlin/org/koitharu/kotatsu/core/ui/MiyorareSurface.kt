package org.koitharu.kotatsu.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * Reusable, finite Modern surface treatment. Classic callers get the original modifier unchanged.
 * The gradient is a static brush derived from theme tokens; no continuous animation or blur is used.
 */
fun Modifier.miyorareSurface(
	palette: MiyorareVisualPalette,
	shape: Shape,
	selectedFraction: Float = 0f,
	drawBorder: Boolean = true,
): Modifier {
	if (!palette.isModern) return this
	val selected = selectedFraction.coerceIn(0f, 1f)
	val start = lerp(palette.surfaceGradientStart, palette.activeGradientStart, selected)
	val end = lerp(palette.surfaceGradientEnd, palette.activeGradientEnd, selected)
	var result = background(
		brush = Brush.horizontalGradient(listOf(start, end)),
		shape = shape,
	)
	if (drawBorder) {
		result = result.border(1.dp, palette.borderHighlight, shape)
	}
	return result
}

/**
 * Static accent gradient for selected controls and primary actions. The effect-level glow token is
 * rendered as a cheap highlight ring rather than a continuous blur/shader, keeping list performance
 * predictable while still making Light/Balanced/Full visibly distinct.
 */
fun Modifier.miyorareAccentSurface(
	palette: MiyorareVisualPalette,
	shape: Shape,
	alpha: Float = 1f,
): Modifier {
	if (!palette.isModern) return this
	val safeAlpha = alpha.coerceIn(0f, 1f)
	return background(
		brush = Brush.horizontalGradient(
			listOf(
				palette.activeGradientStart.copy(alpha = safeAlpha),
				palette.activeGradientEnd.copy(alpha = safeAlpha),
			),
		),
		shape = shape,
	).border(
		width = 1.dp,
		color = palette.glow.copy(alpha = palette.glow.alpha * safeAlpha),
		shape = shape,
	)
}
