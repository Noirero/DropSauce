package org.koitharu.kotatsu.favourites.ui.container

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.Gravity
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator.TabConfigurationStrategy
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.util.PopupMenuMediator
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment.Companion.NO_ID
import java.util.WeakHashMap
import kotlin.math.roundToInt
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

class FavouritesTabConfigurationStrategy(
	private val adapter: FavouritesContainerAdapter,
	private val viewModel: FavouritesContainerViewModel,
	private val router: AppRouter,
	private val modern: Boolean,
) : TabConfigurationStrategy {

	private val baseBackgrounds = WeakHashMap<View, Drawable?>()

	override fun onConfigureTab(tab: TabLayout.Tab, position: Int) {
		val item = adapter.getItem(position)
		val view = tab.view
		if (!baseBackgrounds.containsKey(view)) baseBackgrounds[view] = view.background
		val title = item.title ?: view.context.getString(R.string.all_favourites)
		val style = systemStyle(item.id)
		if (style == null) {
			view.setBackgroundKeepingPadding(createCategoryBackground(view.context))
			tab.text = title
		} else {
			val separator = isLastSystemTab(position)
			view.setBackgroundKeepingPadding(createSystemBackground(view.context, style, separator))
			tab.text = createSystemTitle(view.context, title, style)
		}
		tab.tag = item
		updateFavouriteTabBadge(tab, item.count, item.count > 0)
		if (item.id != LOCAL_FAVOURITES_CATEGORY_ID && item.id != DOWNLOADED_FAVOURITES_CATEGORY_ID) {
			PopupMenuMediator(
				FavouriteTabPopupMenuProvider(view.context, router, viewModel, item.id),
			).attach(view)
		}
	}

	private fun isLastSystemTab(position: Int): Boolean {
		val current = adapter.getItem(position).id
		val next = position + 1
		return current.isSystemCategory() && next < adapter.itemCount && !adapter.getItem(next).id.isSystemCategory()
	}

	private fun createCategoryBackground(context: Context): Drawable = createSystemBackground(
		context = context,
		style = SystemStyle(
			iconRes = R.drawable.ic_tag,
			containerAttr = materialR.attr.colorPrimaryContainer,
			accentAttr = appcompatR.attr.colorPrimary,
		),
		separator = false,
	)

	private fun createSystemBackground(context: Context, style: SystemStyle, separator: Boolean): Drawable {
		val density = context.resources.displayMetrics.density
		val surface = context.getThemeColor(materialR.attr.colorSurface, Color.TRANSPARENT)
		val container = context.getThemeColor(style.containerAttr, surface)
		val accent = context.getThemeColor(style.accentAttr, container)
		val states = arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf())
		val radiusDp = if (modern) MiyorareVisualTokens.RADIUS_CONTROL_DP else 20f
		val selectedFill = if (modern) 0.80f else 0.96f
		val idleFill = if (modern) 0.085f else 0.13f
		val selectedStroke = if (modern) 0.68f else 0.95f
		val idleStroke = if (modern) 0.11f else 0.18f
		val shape = MaterialShapeDrawable(
			ShapeAppearanceModel.builder().setAllCornerSizes(radiusDp * density).build(),
		).apply {
			fillColor = ColorStateList(
				states,
				intArrayOf(
					ColorUtils.blendARGB(surface, container, selectedFill),
					ColorUtils.blendARGB(surface, container, idleFill),
				),
			)
			setStroke(
				(if (modern) 0.75f else 1f) * density,
				ColorStateList(
					states,
					intArrayOf(
						ColorUtils.blendARGB(surface, accent, selectedStroke),
						ColorUtils.blendARGB(surface, accent, idleStroke),
					),
				),
			)
		}
		val horizontal = ((if (modern) 3f else 4f) * density).roundToInt()
		val vertical = ((if (modern) 5f else 4f) * density).roundToInt()
		val separatorSpace = ((if (modern) 8f else 10f) * density).roundToInt()
		val pill = InsetDrawable(
			shape,
			horizontal,
			vertical,
			horizontal + if (separator) separatorSpace else 0,
			vertical,
		)
		val content = if (separator && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			val divider = GradientDrawable().apply {
				setColor(ColorUtils.blendARGB(surface, accent, if (modern) 0.22f else 0.34f))
			}
			LayerDrawable(arrayOf(pill, divider)).apply {
				setLayerSize(1, (1f * density).roundToInt().coerceAtLeast(1), (22f * density).roundToInt())
				setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
				setLayerInsetEnd(1, (2f * density).roundToInt())
			}
		} else {
			pill
		}
		return RippleDrawable(
			ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, if (modern) 32 else 48)),
			content,
			null,
		)
	}

	private fun createSystemTitle(context: Context, title: CharSequence, style: SystemStyle): CharSequence {
		val icon = ContextCompat.getDrawable(context, style.iconRes)?.let { drawable ->
			DrawableCompat.wrap(drawable.mutate()).also {
				DrawableCompat.setTint(it, context.getThemeColor(style.accentAttr, Color.GRAY))
				val size = ((if (modern) 15f else 16f) * context.resources.displayMetrics.density).roundToInt()
				it.setBounds(0, 0, size, size)
			}
		} ?: return title
		return SpannableStringBuilder().apply {
			append('\uFFFC')
			setSpan(
				ImageSpan(
					icon,
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ImageSpan.ALIGN_CENTER else ImageSpan.ALIGN_BOTTOM,
				),
				0,
				1,
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
			)
			append(' ').append(title)
		}
	}

	private fun systemStyle(categoryId: Long): SystemStyle? = when (categoryId) {
		NO_ID -> SystemStyle(R.drawable.ic_heart_outline, materialR.attr.colorPrimaryContainer, appcompatR.attr.colorPrimary)
		DOWNLOADED_FAVOURITES_CATEGORY_ID ->
			SystemStyle(R.drawable.ic_storage, materialR.attr.colorSecondaryContainer, materialR.attr.colorSecondary)
		LOCAL_FAVOURITES_CATEGORY_ID ->
			SystemStyle(R.drawable.ic_folder_file, materialR.attr.colorTertiaryContainer, materialR.attr.colorTertiary)
		else -> null
	}

	private fun Long.isSystemCategory() =
		this == NO_ID || this == DOWNLOADED_FAVOURITES_CATEGORY_ID || this == LOCAL_FAVOURITES_CATEGORY_ID

	private data class SystemStyle(val iconRes: Int, val containerAttr: Int, val accentAttr: Int)
}

private fun View.setBackgroundKeepingPadding(drawable: Drawable?) {
	val start = paddingStart
	val top = paddingTop
	val end = paddingEnd
	val bottom = paddingBottom
	background = drawable
	setPaddingRelative(start, top, end, bottom)
}

private data class FavouriteTabBasePadding(
	val start: Int,
	val top: Int,
	val end: Int,
	val bottom: Int,
)

private val favouriteTabBasePaddings = WeakHashMap<View, FavouriteTabBasePadding>()

/**
 * Material badges are overlays, so their width does not participate in the TabLayout measurement.
 * Reserve only the end space required by the current digit count and move the badge into that space,
 * keeping both long category names and the full count readable without replacing the Material badge.
 */
internal fun updateFavouriteTabBadge(tab: TabLayout.Tab, count: Int, isVisible: Boolean) {
	val safeCount = count.coerceAtLeast(0)
	val shouldShowBadge = isVisible && safeCount > 0
	val visibleDigits = safeCount.coerceAtMost(MAX_CATEGORY_BADGE_COUNT).toString().length
	val view = tab.view
	val density = view.resources.displayMetrics.density
	val basePadding = favouriteTabBasePaddings.getOrPut(view) {
		FavouriteTabBasePadding(
			start = view.paddingStart,
			top = view.paddingTop,
			end = view.paddingEnd,
			bottom = view.paddingBottom,
		)
	}
	val badgeSpace = if (shouldShowBadge) {
		((BADGE_BASE_END_SPACE_DP + visibleDigits * BADGE_PER_DIGIT_SPACE_DP) * density).roundToInt()
	} else {
		0
	}
	view.setPaddingRelative(
		basePadding.start,
		basePadding.top,
		basePadding.end + badgeSpace,
		basePadding.bottom,
	)

	tab.getOrCreateBadge().apply {
		maxCharacterCount = 6
		number = safeCount
		setHorizontalPadding((BADGE_HORIZONTAL_PADDING_DP * density).roundToInt())
		setHorizontalOffsetWithText(-((BADGE_BASE_OUTWARD_OFFSET_DP + visibleDigits * BADGE_PER_DIGIT_OFFSET_DP) * density).roundToInt())
		this.isVisible = shouldShowBadge
	}
}

private const val MAX_CATEGORY_BADGE_COUNT = 99_999
private const val BADGE_BASE_END_SPACE_DP = 16
private const val BADGE_PER_DIGIT_SPACE_DP = 4
private const val BADGE_HORIZONTAL_PADDING_DP = 3
private const val BADGE_BASE_OUTWARD_OFFSET_DP = 7
private const val BADGE_PER_DIGIT_OFFSET_DP = 2
