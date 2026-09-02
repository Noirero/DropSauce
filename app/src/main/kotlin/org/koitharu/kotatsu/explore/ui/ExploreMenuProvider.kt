package org.koitharu.kotatsu.explore.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.explore.data.ExploreContentFilter

class ExploreMenuProvider(
	private val router: AppRouter,
	private val viewModel: ExploreViewModel,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_explore, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		when (viewModel.contentFilter.value) {
			ExploreContentFilter.ALL -> menu.findItem(R.id.action_content_filter_all)?.isChecked = true
			ExploreContentFilter.SFW -> menu.findItem(R.id.action_content_filter_sfw)?.isChecked = true
			ExploreContentFilter.NSFW -> menu.findItem(R.id.action_content_filter_nsfw)?.isChecked = true
		}
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		return when (menuItem.itemId) {
			R.id.action_content_filter_all -> {
				viewModel.setContentFilter(ExploreContentFilter.ALL)
				menuItem.isChecked = true
				true
			}

			R.id.action_content_filter_sfw -> {
				viewModel.setContentFilter(ExploreContentFilter.SFW)
				menuItem.isChecked = true
				true
			}

			R.id.action_content_filter_nsfw -> {
				viewModel.setContentFilter(ExploreContentFilter.NSFW)
				menuItem.isChecked = true
				true
			}

			R.id.action_content_classification_reset_all -> {
				viewModel.resetContentClassifications()
				true
			}

			R.id.action_manage -> {
				router.openSourcesCatalog(isExternalOnly = true)
				true
			}

			else -> false
		}
	}
}
