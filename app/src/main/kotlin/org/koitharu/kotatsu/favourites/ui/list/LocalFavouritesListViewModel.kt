package org.koitharu.kotatsu.favourites.ui.list

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.favourites.domain.FavouritesSearchMatcher
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.ui.MangaListViewModel
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.local.data.LocalFavouritesRepository
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.domain.model.LocalManga
import javax.inject.Inject

@HiltViewModel
class LocalFavouritesListViewModel @Inject constructor(
	private val settings: AppSettings,
	mangaDataRepository: MangaDataRepository,
	@LocalStorageChanges private val localStorageChanges: SharedFlow<LocalManga?>,
	private val localFavouritesRepository: LocalFavouritesRepository,
	private val mangaListMapper: MangaListMapper,
	private val searchMatcher: FavouritesSearchMatcher,
) : MangaListViewModel(settings, mangaDataRepository, localStorageChanges) {

	override val listMode = settings.observeAsFlow(AppSettings.KEY_LIST_MODE_FAVORITES) { favoritesListMode }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.favoritesListMode)

	override val content = combine(
		localFavouritesRepository.items,
		observeListModeWithTriggers(),
		FavouritesContainerFragment.searchQuery,
	) { items, mode, query ->
		val visible = searchMatcher.filter(items.skipNsfwIfNeeded(), query)
		if (visible.isEmpty()) {
			listOf(
				EmptyState(
					icon = R.drawable.ic_empty_favourites,
					textPrimary = if (query.isBlank()) {
						R.string.text_empty_holder_primary
					} else {
						R.string.nothing_found
					},
					textSecondary = if (query.isBlank()) {
						R.string.favourites_category_empty
					} else {
						R.string.text_empty_holder_secondary_filtered
					},
					actionStringRes = 0,
				),
			)
		} else {
			ArrayList<ListModel>(visible.size).also { result ->
				mangaListMapper.toListModelList(
					destination = result,
					manga = visible,
					mode = mode,
					flags = MangaListMapper.NO_FAVORITE,
				)
			}
		}
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		listOf(LoadingState),
	)

	init {
		onRefresh()
		viewModelScope.launch {
			localStorageChanges.collect {
				localFavouritesRepository.refresh()
			}
		}
	}

	override fun onRefresh() {
		launchLoadingJob(Dispatchers.IO) {
			localFavouritesRepository.refresh()
		}
	}

	override fun onRetry() = onRefresh()
}
