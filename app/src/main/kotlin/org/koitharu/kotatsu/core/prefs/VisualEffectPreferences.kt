package org.koitharu.kotatsu.core.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.R
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class MiyorareDesignStyle {
	CLASSIC,
	MODERN,
}

enum class MiyorareThemePreset(val accentArgb: Int) {
	MIYORARE(0xFF5B6CFF.toInt()),
	SAKURA(0xFFE85D9E.toInt()),
	VIOLET(0xFF7C5CFF.toInt()),
	CYAN(0xFF16AFC4.toInt()),
	EMERALD(0xFF2FA97D.toInt()),
	AMBER(0xFFC47B1C.toInt()),
	CUSTOM(0xFF5B6CFF.toInt()),
}

enum class VisualEffectLevel(
	@StringRes val titleResId: Int,
	val surfaceTintFraction: Float,
	val headerElevationDp: Float,
	val outlineAlpha: Int,
) {
	LIGHT(
		R.string.visual_effects_light,
		surfaceTintFraction = 0.025f,
		headerElevationDp = 0f,
		outlineAlpha = 24,
	),
	BALANCED(
		R.string.visual_effects_balanced,
		surfaceTintFraction = 0.12f,
		headerElevationDp = 3f,
		outlineAlpha = 80,
	),
	FULL(
		R.string.visual_effects_full,
		surfaceTintFraction = 0.20f,
		headerElevationDp = 6f,
		outlineAlpha = 120,
	),
}

/**
 * Central preference gateway for the Miyorare visual layer.
 *
 * It intentionally uses the same default SharedPreferences file as [AppSettings], so the modern
 * appearance system never creates a second preference store. Classic remains the safe default;
 * screens only opt into Miyorare Modern after [KEY_DESIGN_STYLE] is set to [MiyorareDesignStyle.MODERN].
 */
@Singleton
class VisualEffectPreferences @Inject constructor(
	@ApplicationContext context: Context,
) {
	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	private val mutableLevel = MutableStateFlow(readLevel())
	private val mutableDesignStyle = MutableStateFlow(readDesignStyle())
	private val mutableThemePreset = MutableStateFlow(readThemePreset())
	private val mutableCustomAccent = MutableStateFlow(readCustomAccent())

	val level: StateFlow<VisualEffectLevel> = mutableLevel.asStateFlow()
	val designStyle: StateFlow<MiyorareDesignStyle> = mutableDesignStyle.asStateFlow()
	val themePreset: StateFlow<MiyorareThemePreset> = mutableThemePreset.asStateFlow()
	val customAccent: StateFlow<String> = mutableCustomAccent.asStateFlow()

	private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		when (key) {
			KEY_LEVEL -> mutableLevel.value = readLevel()
			KEY_DESIGN_STYLE -> mutableDesignStyle.value = readDesignStyle()
			KEY_THEME_PRESET -> mutableThemePreset.value = readThemePreset()
			KEY_CUSTOM_ACCENT -> mutableCustomAccent.value = readCustomAccent()
		}
	}

	init {
		prefs.registerOnSharedPreferenceChangeListener(listener)
	}

	fun setLevel(value: VisualEffectLevel) {
		prefs.edit().putString(KEY_LEVEL, value.name).apply()
	}

	fun setDesignStyle(value: MiyorareDesignStyle) {
		prefs.edit().putString(KEY_DESIGN_STYLE, value.name).apply()
	}

	fun setThemePreset(value: MiyorareThemePreset) {
		prefs.edit().putString(KEY_THEME_PRESET, value.name).apply()
	}

	/** Returns false and leaves the previous value untouched when [value] is not #RRGGBB. */
	fun setCustomAccent(value: String): Boolean {
		val normalized = normalizeAccent(value) ?: return false
		prefs.edit().putString(KEY_CUSTOM_ACCENT, normalized).apply()
		return true
	}

	/**
	 * Reset only display/theme choices. List density, reader settings and other unrelated appearance
	 * preferences are deliberately preserved.
	 */
	fun resetAppearance() {
		prefs.edit()
			.putString(KEY_DESIGN_STYLE, MiyorareDesignStyle.CLASSIC.name)
			.putString(KEY_THEME_PRESET, MiyorareThemePreset.MIYORARE.name)
			.putString(KEY_CUSTOM_ACCENT, DEFAULT_CUSTOM_ACCENT)
			.putString(KEY_LEVEL, VisualEffectLevel.BALANCED.name)
			.putString(AppSettings.KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM.toString())
			.putBoolean(AppSettings.KEY_THEME_AMOLED, false)
			.apply()
		AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
	}

	private fun readLevel(): VisualEffectLevel {
		val raw = prefs.getString(KEY_LEVEL, null)
		return VisualEffectLevel.entries.firstOrNull { it.name == raw } ?: VisualEffectLevel.BALANCED
	}

	private fun readDesignStyle(): MiyorareDesignStyle {
		val raw = prefs.getString(KEY_DESIGN_STYLE, null)
		return MiyorareDesignStyle.entries.firstOrNull { it.name == raw } ?: MiyorareDesignStyle.CLASSIC
	}

	private fun readThemePreset(): MiyorareThemePreset {
		val raw = prefs.getString(KEY_THEME_PRESET, null)
		return MiyorareThemePreset.entries.firstOrNull { it.name == raw } ?: MiyorareThemePreset.MIYORARE
	}

	private fun readCustomAccent(): String {
		return normalizeAccent(prefs.getString(KEY_CUSTOM_ACCENT, null).orEmpty()) ?: DEFAULT_CUSTOM_ACCENT
	}

	companion object {
		const val KEY_LEVEL = "visual_effect_level"
		const val KEY_DESIGN_STYLE = "miyorare_design_style"
		const val KEY_THEME_PRESET = "miyorare_theme_preset"
		const val KEY_CUSTOM_ACCENT = "miyorare_custom_accent"
		const val DEFAULT_CUSTOM_ACCENT = "#5B6CFF"

		fun parseAccentArgb(value: String): Int? {
			val raw = value.trim().removePrefix("#")
			if (raw.length != 6 || raw.any { it.digitToIntOrNull(16) == null }) return null
			return ((raw.toLong(16) and 0x00FFFFFFL) or 0xFF000000L).toInt()
		}

		fun normalizeAccent(value: String): String? {
			val argb = parseAccentArgb(value) ?: return null
			return String.format(Locale.ROOT, "#%06X", argb and 0x00FFFFFF)
		}
	}
}
