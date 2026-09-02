package org.koitharu.kotatsu.favourites.ui.container

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.domain.FavouriteDisplayPreferences
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesSearchMatcher
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_TITLE
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment.Companion.NO_ID
import org.koitharu.kotatsu.local.data.LocalFavouritesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

@HiltViewModel
class FavouritesContainerViewModel @Inject constructor(
	private val settings: AppSettings,
	private val favouritesRepository: FavouritesRepository,
	private val searchMatcher: FavouritesSearchMatcher,
	private val contentTypeStore: FavouriteContentTypeStore,
	private val localFavouritesRepository: LocalFavouritesRepository,
	private val displayPreferences: FavouriteDisplayPreferences,
) : BaseViewModel() {

	val onActionDone = MutableEventFlow<ReversibleAction>()

	private val categoriesStateFlow = favouritesRepository.observeCategoriesForLibrary()
		.withErrorHandling()
		// A category sort-order change only changes the manga order inside that page. Do not rebuild
		// every tab/count for it; the page ViewModel observes the order itself and refreshes immediately.
		.distinctUntilChanged { old, new ->
			old.size == new.size && old.indices.all { index ->
				old[index].id == new[index].id && old[index].title == new[index].title
			}
		}
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	private val contentTypeState = combine(
		contentTypeStore.selectedType,
		contentTypeStore.novelCategoryIds,
		localFavouritesRepository.items,
		displayPreferences.state,
	) { type, _, localManga, preferences ->
		ContentTypeState(
			type = type,
			localManga = localManga,
			showCategoryCounts = preferences.getValue(type).showCategoryCounts,
		)
	}

	val categories = combine(
		categoriesStateFlow.filterNotNull(),
		observeAllFavouritesVisibility(),
		// We only need to know that favourites changed. Loading every cover for every category here
		// made large libraries rebuild slowly even though this ViewModel never used those covers.
		favouritesRepository.observeFavouritesChanges(),
		contentTypeState,
		FavouritesContainerFragment.searchQuery,
	) { list, showAll, _, state, query ->
		val type = state.type
		val typedCategories = list.filter { contentTypeStore.isCategoryForType(it.id, type) }
		val wantNovel = type == FavouriteContentType.NOVEL

		// Category counts are an optional presentation detail. When hidden, do not scan the whole
		// library and every category just to compute numbers that will never be rendered.
		if (!state.showCategoryCounts) {
			return@combine typedCategories.toUi(
				showAll = showAll,
				allCount = 0,
				counts = emptyMap(),
				includeLocal = !wantNovel,
				localCount = 0,
			)
		}

		val allForType = favouritesRepository.getAllManga().filter { it.source.isNovelSource == wantNovel }
		val matchingIds = if (query.isBlank()) {
			allForType.mapTo(HashSet(allForType.size)) { it.id }
		} else {
			searchMatcher.filter(allForType, query).mapTo(HashSet()) { it.id }
		}
		val counts = buildMap<Long, Int> {
			for (category in typedCategories) {
				put(
					category.id,
					favouritesRepository.getManga(category.id).count { manga ->
						manga.source.isNovelSource == wantNovel && manga.id in matchingIds
					},
				)
			}
		}
		val localCount = if (wantNovel) {
			0
		} else if (query.isBlank()) {
			state.localManga.size
		} else {
			searchMatcher.filter(state.localManga, query).size
		}
		typedCategories.toUi(
			showAll = showAll,
			allCount = matchingIds.size,
			counts = counts,
			includeLocal = !wantNovel,
			localCount = localCount,
		)
	}.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val isEmpty = categories.map { it.isEmpty() }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	private fun List<FavouriteCategory>.toUi(
		showAll: Boolean,
		allCount: Int,
		counts: Map<Long, Int>,
		includeLocal: Boolean,
		localCount: Int,
	): List<FavouriteTabModel> {
		if (isEmpty() && !showAll && !includeLocal) return emptyList()
		val result = ArrayList<FavouriteTabModel>(
			size + (if (showAll) 1 else 0) + (if (includeLocal) 1 else 0),
		)
		if (showAll) result.add(FavouriteTabModel(NO_ID, null, allCount))
		if (includeLocal) {
			result.add(
				FavouriteTabModel(
					LOCAL_FAVOURITES_CATEGORY_ID,
					LOCAL_FAVOURITES_CATEGORY_TITLE,
					localCount,
				),
			)
		}
		mapTo(result) { FavouriteTabModel(it.id, it.title, counts[it.id] ?: 0) }
		return result
	}

	fun hide(categoryId: Long) {
		if (categoryId == LOCAL_FAVOURITES_CATEGORY_ID) return
		launchJob(Dispatchers.Default) {
			if (categoryId == NO_ID) {
				settings.isAllFavouritesVisible = false
			} else {
				favouritesRepository.updateCategory(categoryId, isVisibleInLibrary = false)
				val reverse = ReversibleHandle {
					favouritesRepository.updateCategory(categoryId, isVisibleInLibrary = true)
				}
				onActionDone.call(ReversibleAction(R.string.category_hidden_done, reverse))
			}
		}
	}

	fun deleteCategory(categoryId: Long) {
		if (categoryId == LOCAL_FAVOURITES_CATEGORY_ID) return
		launchJob(Dispatchers.Default) {
			favouritesRepository.removeCategories(setOf(categoryId))
			contentTypeStore.removeCategories(setOf(categoryId))
		}
	}

	private fun observeAllFavouritesVisibility() = settings.observeAsFlow(
		key = AppSettings.KEY_ALL_FAVOURITES_VISIBLE,
		valueProducer = { isAllFavouritesVisible },
	)

	private data class ContentTypeState(
		val type: FavouriteContentType,
		val localManga: List<Manga>,
		val showCategoryCounts: Boolean,
	)
}