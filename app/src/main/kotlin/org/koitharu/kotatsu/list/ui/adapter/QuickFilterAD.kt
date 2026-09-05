package org.koitharu.kotatsu.list.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import androidx.preference.PreferenceManager
import com.google.android.material.chip.Chip
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.getEnumValue
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.databinding.ItemQuickFilterBinding
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.ui.model.ExtensionFilter
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.QuickFilter
import com.google.android.material.R as materialR

fun quickFilterAD(
	listener: QuickFilterClickListener,
) = adapterDelegateViewBinding<QuickFilter, ListModel, ItemQuickFilterBinding>(
	{ layoutInflater, parent -> ItemQuickFilterBinding.inflate(layoutInflater, parent, false) }
) {

	binding.chipsTags.onChipClickListener = ChipsView.OnChipClickListener { chip, data ->
		when (data) {
			is ListFilterOption -> listener.onFilterOptionClick(data)
			is ExtensionFilter -> ExtensionFilterPopup.show(chip, data, listener)
		}
	}

	bind {
		binding.chipsTags.setChips(item.items)
		binding.applyMiyorareFavouritesQuickFilterStyle(item)
	}
}

/**
 * Keeps the shared quick-filter adapter neutral by default and applies the compact Miyorare treatment
 * only to the Favourites quick-filter row. The same adapter is used by other list screens, so neither
 * Classic Favourites nor unrelated quick filters should inherit this visual pass.
 */
private fun ItemQuickFilterBinding.applyMiyorareFavouritesQuickFilterStyle(item: QuickFilter) {
	val isFavouritesQuickFilter = item.items.any { it.titleResId == R.string.favorites_continue_reading } &&
		item.items.any { it.titleResId == R.string.favorites_filter }
	if (!isFavouritesQuickFilter) return

	val preferences = PreferenceManager.getDefaultSharedPreferences(root.context)
	val designStyle = preferences.getEnumValue(
		MiyorareAppearance.KEY_DESIGN_STYLE,
		MiyorareDesignStyle.CLASSIC,
	)
	if (designStyle != MiyorareDesignStyle.MODERN) return

	chipsTags.applyMiyorareFavouritesQuickFilterStyle()
}

private fun ChipsView.applyMiyorareFavouritesQuickFilterStyle() {
	val density = resources.displayMetrics.density
	val primary = context.getThemeColor(materialR.attr.colorPrimary, Color.WHITE)
	val surface = context.getThemeColor(materialR.attr.colorSurfaceContainer, Color.DKGRAY)
	val surfaceHigh = context.getThemeColor(materialR.attr.colorSurfaceContainerHighest, surface)
	val onSurface = context.getThemeColor(materialR.attr.colorOnSurface, Color.WHITE)
	val onSurfaceVariant = context.getThemeColor(materialR.attr.colorOnSurfaceVariant, onSurface)
	val outline = context.getThemeColor(materialR.attr.colorOutlineVariant, primary)
	val controlRadius = MiyorareVisualTokens.RADIUS_CONTROL_DP * density

	chipSpacingHorizontal = (MiyorareVisualTokens.SPACING_S_DP * density).toInt()
	children.forEach { child ->
		val chip = child as? Chip ?: return@forEach
		val selected = chip.isChecked
		val container = if (selected) {
			ColorUtils.blendARGB(surfaceHigh, primary, MiyorareVisualTokens.ACTIVE_GRADIENT_MIX * 0.52f)
		} else {
			ColorUtils.blendARGB(surface, primary, MiyorareVisualTokens.GLOW_ALPHA_LIGHT)
		}
		val strokeBase = if (selected) primary else outline
		val strokeAlpha = if (selected) {
			MiyorareVisualTokens.BORDER_ALPHA_BALANCED
		} else {
			MiyorareVisualTokens.BORDER_ALPHA_LIGHT
		}
		val stroke = ColorUtils.setAlphaComponent(
			strokeBase,
			(strokeAlpha * 255f).toInt().coerceIn(0, 255),
		)
		val contentColor = if (selected) onSurface else onSurfaceVariant

		chip.chipMinHeight = controlRadius * 2f
		chip.chipCornerRadius = controlRadius
		chip.chipIconSize = controlRadius
		chip.chipStrokeWidth = density * if (selected) 1f else 0.75f
		chip.chipBackgroundColor = ColorStateList.valueOf(container)
		chip.chipStrokeColor = ColorStateList.valueOf(stroke)
		chip.setTextColor(contentColor)
		chip.chipIconTint = ColorStateList.valueOf(contentColor)
		chip.closeIconTint = ColorStateList.valueOf(contentColor)
		chip.rippleColor = ColorStateList.valueOf(
			ColorUtils.setAlphaComponent(primary, (MiyorareVisualTokens.GLOW_ALPHA_BALANCED * 255f).toInt()),
		)
		chip.elevation = 0f
	}
}
