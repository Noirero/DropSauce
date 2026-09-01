package org.koitharu.kotatsu.favourites.ui.list

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.databinding.FragmentListBinding
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.list.ui.MangaListFragment
import org.koitharu.kotatsu.list.ui.adapter.MangaListAdapter
import org.koitharu.kotatsu.list.ui.config.ListConfigSection
import org.koitharu.kotatsu.list.ui.size.DynamicItemSizeResolver

@AndroidEntryPoint
class LocalFavouritesListFragment : MangaListFragment() {

	override val viewModel by viewModels<LocalFavouritesListViewModel>()

	override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.recyclerView.isVP2BugWorkaroundEnabled = true
	}

	override fun onResume() {
		super.onResume()
		viewModel.onRefresh()
	}

	override fun onCreateAdapter() = MangaListAdapter(
		listener = this,
		sizeResolver = DynamicItemSizeResolver(resources, viewLifecycleOwner, settings, adjustWidth = false),
		titleTapToRead = settings.isTitleTapToReadEnabled,
	)

	override fun onScrolledToEnd() = Unit

	override fun onFilterClick(view: View?) {
		router.showListSortSheet(ListConfigSection.Favorites(LOCAL_FAVOURITES_CATEGORY_ID))
	}

	override fun onEmptyActionClick() {
		viewModel.onRefresh()
	}
}
