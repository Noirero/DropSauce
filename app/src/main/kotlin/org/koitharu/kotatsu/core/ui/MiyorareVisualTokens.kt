package org.koitharu.kotatsu.core.ui

/**
 * Shared dimensions and motion timings for the Miyorare visual language. Keeping these values in
 * one lightweight object prevents individual screens from drifting into unrelated radii/spacing.
 */
object MiyorareVisualTokens {
	const val RADIUS_SMALL_DP = 12f
	const val RADIUS_CONTROL_DP = 18f
	const val RADIUS_CARD_DP = 20f
	const val RADIUS_SURFACE_DP = 24f
	const val RADIUS_DIALOG_DP = 32f

	const val SPACING_COMPACT_DP = 8f
	const val SPACING_STANDARD_DP = 16f
	const val SPACING_SECTION_DP = 24f

	const val MOTION_QUICK_MS = 140
	const val MOTION_STANDARD_MS = 220
}
