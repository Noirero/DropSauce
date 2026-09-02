package org.koitharu.kotatsu.favourites.ui.list

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.favourites.domain.FavouritesSearchMatcher
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.ui.MangaListViewModel
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.list.ui.model.MangaDetailedListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
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

	val pinnedIds: StateFlow<List<Long>> = settings.observeAsFlow(
		AppSettings.KEY_FAVORITES_PINNED + LOCAL_FAVOURITES_CATEGORY_ID,
	) { getPinnedFavourites(LOCAL_FAVOURITES_CATEGORY_ID) }.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		settings.getPinnedFavourites(LOCAL_FAVOURITES_CATEGORY_ID),
	)

	override val content = combine(
		localFavouritesRepository.items,
		observeListModeWithTriggers(),
		FavouritesContainerFragment.searchQuery,
		pinnedIds,
	) { items, mode, query, pinned ->
		val searched = searchMatcher.filter(items.skipNsfwIfNeeded(), query)
		val pinnedSet = pinned.toHashSet()
		val visible = if (pinnedSet.isEmpty()) {
			searched
		} else {
			val ordered = ArrayList<org.koitharu.kotatsu.parsers.model.Manga>(searched.size)
			for (id in pinned) {
				searched.firstOrNull { it.id == id }?.let(ordered::add)
			}
			for (manga in searched) {
				if (manga.id !in pinnedSet) ordered.add(manga)
			}
			ordered
		}
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
				if (pinnedSet.isNotEmpty()) {
					for (i in result.indices) {
						val model = result[i]
						if (model !is MangaListModel || model.manga.id !in pinnedSet) continue
						result[i] = when (model) {
							is MangaGridModel -> model.copy(isPinned = true)
							is MangaDetailedListModel -> model.copy(isPinned = true)
							is MangaCompactListModel -> model.copy(isPinned = true)
						}
					}
				}
			}
		}
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		listOf(LoadingState),
	)

	init {
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

	fun setPinned(ids: Set<Long>, isPinned: Boolean) {
		val current = settings.getPinnedFavourites(LOCAL_FAVOURITES_CATEGORY_ID)
		val updated = if (isPinned) current + (ids - current.toSet()) else current - ids
		settings.setPinnedFavourites(LOCAL_FAVOURITES_CATEGORY_ID, updated)
	}
}
