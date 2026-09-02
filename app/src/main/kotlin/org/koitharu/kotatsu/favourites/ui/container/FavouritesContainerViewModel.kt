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
import org.koitharu.kotatsu.core.model.MangaSource
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

	/**
	 * Category structure is deliberately independent from favourite-count calculation. With very large
	 * libraries the user should see the category tabs as soon as the tiny category query finishes; badge
	 * numbers can arrive afterwards as a count-only update.
	 */
	private val categoryStructure = combine(
		categoriesStateFlow.filterNotNull(),
		observeAllFavouritesVisibility(),
		contentTypeStore.selectedType,
	) { list, showAll, type ->
		CategoryStructure(
			type = type,
			categories = list.filter { contentTypeStore.isCategoryForType(it.id, type) },
			showAll = showAll,
			includeLocal = type != FavouriteContentType.NOVEL,
		)
	}.distinctUntilChanged()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	/**
	 * Counts are computed in the background from one lightweight membership query. The old path loaded
	 * every Manga+tags once for the whole library and then again once per category (N+1); a 16k library
	 * spread over ~30 categories could therefore materialise hundreds of thousands of Manga objects
	 * before the tabs appeared.
	 */
	private val countState = combine(
		categoriesStateFlow.filterNotNull(),
		favouritesRepository.observeFavouritesChanges(),
		contentTypeState,
		FavouritesContainerFragment.searchQuery,
	) { list, _, state, query ->
		val typedCategories = list.filter { contentTypeStore.isCategoryForType(it.id, state.type) }
		val key = CountKey(
			type = state.type,
			query = query,
			categoryIds = typedCategories.map { it.id },
		)
		if (!state.showCategoryCounts) {
			return@combine CountSnapshot(key, 0, emptyMap(), 0)
		}

		val remote = calculateRemoteCounts(typedCategories, state.type, query)
		val localCount = if (state.type == FavouriteContentType.NOVEL) {
			0
		} else if (query.isBlank()) {
			state.localManga.size
		} else {
			searchMatcher.filter(state.localManga, query).size
		}
		CountSnapshot(
			key = key,
			allCount = remote.allCount,
			counts = remote.counts,
			localCount = localCount,
		)
	}.withErrorHandling()
		.distinctUntilChanged()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, CountSnapshot.EMPTY)

	val categories = combine(
		categoryStructure.filterNotNull(),
		countState,
		FavouritesContainerFragment.searchQuery,
	) { structure, snapshot, query ->
		val expectedKey = CountKey(
			type = structure.type,
			query = query,
			categoryIds = structure.categories.map { it.id },
		)
		val counts = snapshot.takeIf { it.key == expectedKey }
		structure.categories.toUi(
			showAll = structure.showAll,
			allCount = counts?.allCount ?: 0,
			counts = counts?.counts.orEmpty(),
			includeLocal = structure.includeLocal,
			localCount = counts?.localCount ?: 0,
		)
	}.withErrorHandling()
		// Badge-only updates are handled directly by FavouritesContainerAdapter and no longer cause
		// TabLayoutMediator to rebuild all tabs.
		.distinctUntilChanged()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val isEmpty = categories.map { it.isEmpty() }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	private suspend fun calculateRemoteCounts(
		typedCategories: List<FavouriteCategory>,
		type: FavouriteContentType,
		query: String,
	): RemoteCounts {
		val memberships = favouritesRepository.getMemberships()
		val categoryIds = typedCategories.mapTo(HashSet(typedCategories.size)) { it.id }
		val counts = HashMap<Long, Int>(typedCategories.size)
		val wantNovel = type == FavouriteContentType.NOVEL

		if (query.isBlank()) {
			val allIds = HashSet<Long>()
			val sourceTypeCache = HashMap<String, Boolean>()
			for (membership in memberships) {
				val isNovel = sourceTypeCache.getOrPut(membership.source) {
					MangaSource(membership.source).isNovelSource
				}
				if (isNovel != wantNovel) continue
				allIds.add(membership.mangaId)
				if (membership.categoryId in categoryIds) {
					counts[membership.categoryId] = (counts[membership.categoryId] ?: 0) + 1
				}
			}
			return RemoteCounts(allIds.size, counts)
		}

		// Text matching needs Manga titles/authors, so only the active search path loads full Manga
		// objects. Category membership still comes from the single lightweight query above.
		val allForType = favouritesRepository.getAllManga().filter { it.source.isNovelSource == wantNovel }
		val matchingIds = searchMatcher.filter(allForType, query).mapTo(HashSet()) { it.id }
		for (membership in memberships) {
			if (membership.mangaId !in matchingIds || membership.categoryId !in categoryIds) continue
			counts[membership.categoryId] = (counts[membership.categoryId] ?: 0) + 1
		}
		return RemoteCounts(matchingIds.size, counts)
	}

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

	private data class CategoryStructure(
		val type: FavouriteContentType,
		val categories: List<FavouriteCategory>,
		val showAll: Boolean,
		val includeLocal: Boolean,
	)

	private data class CountKey(
		val type: FavouriteContentType,
		val query: String,
		val categoryIds: List<Long>,
	)

	private data class CountSnapshot(
		val key: CountKey?,
		val allCount: Int,
		val counts: Map<Long, Int>,
		val localCount: Int,
	) {
		companion object {
			val EMPTY = CountSnapshot(null, 0, emptyMap(), 0)
		}
	}

	private data class RemoteCounts(
		val allCount: Int,
		val counts: Map<Long, Int>,
	)
}
