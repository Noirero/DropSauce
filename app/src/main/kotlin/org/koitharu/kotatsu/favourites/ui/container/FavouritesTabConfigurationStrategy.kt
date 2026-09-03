package org.koitharu.kotatsu.favourites.ui.container

import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator.TabConfigurationStrategy
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.util.PopupMenuMediator
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import kotlin.math.roundToInt

class FavouritesTabConfigurationStrategy(
	private val adapter: FavouritesContainerAdapter,
	private val viewModel: FavouritesContainerViewModel,
	private val router: AppRouter,
) : TabConfigurationStrategy {

	override fun onConfigureTab(tab: TabLayout.Tab, position: Int) {
		val item = adapter.getItem(position)
		tab.text = item.title ?: tab.view.context.getString(R.string.all_favourites)
		tab.tag = item
		updateFavouriteTabBadge(tab, item.count, item.count > 0)
		if (item.id != LOCAL_FAVOURITES_CATEGORY_ID && item.id != DOWNLOADED_FAVOURITES_CATEGORY_ID) {
			PopupMenuMediator(
				FavouriteTabPopupMenuProvider(tab.view.context, router, viewModel, item.id),
			).attach(tab.view)
		}
	}
}

/**
 * Material badges are overlays, so their width does not participate in the TabLayout measurement.
 * Reserve only the end space required by the current digit count and move the badge into that space,
 * keeping both long category names and the full count readable without replacing the Material badge.
 */
internal fun updateFavouriteTabBadge(tab: TabLayout.Tab, count: Int, isVisible: Boolean) {
	val safeCount = count.coerceAtLeast(0)
	val visibleDigits = safeCount.coerceAtMost(MAX_CATEGORY_BADGE_COUNT).toString().length
	val view = tab.view
	val density = view.resources.displayMetrics.density
	val basePadding = view.paddingStart
	val badgeSpace = ((BADGE_BASE_END_SPACE_DP + visibleDigits * BADGE_PER_DIGIT_SPACE_DP) * density).roundToInt()
	view.setPaddingRelative(
		basePadding,
		view.paddingTop,
		basePadding + badgeSpace,
		view.paddingBottom,
	)

	tab.getOrCreateBadge().apply {
		maxCharacterCount = 6
		number = safeCount
		setHorizontalPadding((BADGE_HORIZONTAL_PADDING_DP * density).roundToInt())
		setHorizontalOffsetWithText(-((BADGE_BASE_OUTWARD_OFFSET_DP + visibleDigits * BADGE_PER_DIGIT_OFFSET_DP) * density).roundToInt())
		this.isVisible = isVisible && safeCount > 0
	}
}

private const val MAX_CATEGORY_BADGE_COUNT = 99_999
private const val BADGE_BASE_END_SPACE_DP = 16
private const val BADGE_PER_DIGIT_SPACE_DP = 4
private const val BADGE_HORIZONTAL_PADDING_DP = 3
private const val BADGE_BASE_OUTWARD_OFFSET_DP = 7
private const val BADGE_PER_DIGIT_OFFSET_DP = 2
