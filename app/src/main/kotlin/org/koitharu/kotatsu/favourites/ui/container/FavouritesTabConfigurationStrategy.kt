package org.koitharu.kotatsu.favourites.ui.container

import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator.TabConfigurationStrategy
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.util.PopupMenuMediator
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID

class FavouritesTabConfigurationStrategy(
	private val adapter: FavouritesContainerAdapter,
	private val viewModel: FavouritesContainerViewModel,
	private val router: AppRouter,
) : TabConfigurationStrategy {

	override fun onConfigureTab(tab: TabLayout.Tab, position: Int) {
		val item = adapter.getItem(position)
		tab.text = item.title ?: tab.view.context.getString(R.string.all_favourites)
		tab.tag = item
		tab.getOrCreateBadge().apply {
			// Material's default badge width abbreviates values above 999 as "999+".
			// Allow the full category count to stay visible through 99,999 instead.
			maxCharacterCount = 6
			number = item.count
			isVisible = item.count > 0
		}
		if (item.id != LOCAL_FAVOURITES_CATEGORY_ID) {
			PopupMenuMediator(
				FavouriteTabPopupMenuProvider(tab.view.context, router, viewModel, item.id),
			).attach(tab.view)
		}
	}
}
