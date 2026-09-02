package org.koitharu.kotatsu.favourites.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getLanguageCode
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.flattenLatest
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType
import org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore
import org.koitharu.kotatsu.favourites.domain.FavouriteDisplayPreferences
import org.koitharu.kotatsu.favourites.domain.FavouriteUnreadCounter
import org.koitharu.kotatsu.favourites.domain.FavoritesListQuickFilter
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesSearchMatcher
import org.koitharu.kotatsu.favourites.domain.debounceFavouritesSearch
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment.Companion.NO_ID
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.history.domain.MarkAsReadUseCase
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.domain.QuickFilterListener
import org.koitharu.kotatsu.list.ui.MangaListViewModel
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.list.ui.model.MangaDetailedListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.model.TIP_UI_SCALING
import org.koitharu.kotatsu.list.ui.model.toErrorState
import org.koitharu.kotatsu.list.ui.model.uiScalingTip
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val PAGE_SIZE = 16
private const val DATABASE_WINDOW_INITIAL = PAGE_SIZE * 4
private const val DATABASE_WINDOW_MAX = 4096

@HiltViewModel
class FavouritesListViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val repository: FavouritesRepository,
	private val mangaListMapper: MangaListMapper,
	private val markAsReadUseCase: MarkAsReadUseCase,
	quickFilterFactory: FavoritesListQuickFilter.Factory,
	private val settings: AppSettings,
	mangaDataRepository: MangaDataRepository,
	@LocalStorageChanges localStorageChanges: SharedFlow<LocalManga?>,
	private val searchMatcher: FavouritesSearchMatcher,
	private val contentTypeStore: FavouriteContentTypeStore,
	private val displayPreferences: FavouriteDisplayPreferences,
	private val localMangaIndex: LocalMangaIndex,
	private val historyRepository: HistoryRepository,
	private val unreadCounter: FavouriteUnreadCounter,
) : MangaListViewModel(settings, mangaDataRepository, localStorageChanges), QuickFilterListener {

	val categoryId: Long = savedStateHandle[AppRouter.KEY_ID] ?: NO_ID
	private val quickFilter = quickFilterFactory.create(categoryId)
	private val refreshTrigger = MutableStateFlow(Any())
	private val limit = MutableStateFlow(PAGE_SIZE)
	private val databaseWindow = MutableStateFlow(DATABASE_WINDOW_INITIAL)
	private val isPaginationReady = AtomicBoolean(false)
	private var lastSortOrder: ListSortOrder? = null
	private var lastFilters: Set<ListFilterOption>? = null
	private var lastContentType: FavouriteContentType? = null
	private var lastSearchQuery = FavouritesContainerFragment.searchQuery.value.trim()

	/**
	 * Share one debounced query between DB-window decisions and UI filtering. Every genuinely new query
	 * starts from a small 64-row database window and 16 visible results; the window grows only when the
	 * current sorted slice does not contain enough matches.
	 */
	private val searchQuery = FavouritesContainerFragment.searchQuery
		.debounceFavouritesSearch()
		.onEach { query ->
			if (query != lastSearchQuery) {
				lastSearchQuery = query
				limit.value = PAGE_SIZE
				databaseWindow.value = DATABASE_WINDOW_INITIAL
			}
		}
		.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.Eagerly,
			lastSearchQuery,
		)

	private val activeDisplayOptions = combine(
		contentTypeStore.selectedType,
		displayPreferences.state,
	) { type, state -> state.getValue(type) }.distinctUntilChanged()

	private val displayState = combine(
		searchQuery,
		contentTypeStore.selectedType,
		limit,
		displayPreferences.state,
	) { query, type, pageLimit, preferences ->
		DisplayState(query, type, pageLimit, preferences.getValue(type))
	}

	override val listMode: StateFlow<ListMode> = activeDisplayOptions
		.map { it.listMode }
		.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.Eagerly,
			displayPreferences.current(contentTypeStore.selectedType.value).listMode,
		)

	override val gridScale: StateFlow<Float> = activeDisplayOptions
		.map { it.gridSize / 100f }
		.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.Eagerly,
			displayPreferences.current(contentTypeStore.selectedType.value).gridSize / 100f,
		)

	override val gridColumns: StateFlow<Int?> = activeDisplayOptions
		.map { it.gridColumns as Int? }
		.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.Eagerly,
			displayPreferences.current(contentTypeStore.selectedType.value).gridColumns,
		)

	val sortOrder: StateFlow<ListSortOrder?> = if (categoryId == NO_ID) {
		settings.observeAsFlow(AppSettings.KEY_FAVORITES_ORDER) { allFavoritesSortOrder }
	} else {
		repository.observeCategory(categoryId).withErrorHandling().map { it?.order }
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val pinnedIds: StateFlow<List<Long>> = settings.observeAsFlow(
		AppSettings.KEY_FAVORITES_PINNED + categoryId,
	) { getPinnedFavourites(categoryId) }.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		settings.getPinnedFavourites(categoryId),
	)

	override val content = combine(
		observeFavorites(),
		observeListModeWithTriggers(),
		combine(
			refreshTrigger,
			settings.observeAsFlow(AppSettings.KEY_TIPS_CLOSED) { isTipEnabled(TIP_UI_SCALING) },
		) { _, visible -> visible },
		pinnedIds,
		displayState,
	) { list, _, scalingTip, pinned, display ->
		val filters = quickFilter.appliedOptions.value
		val wantNovel = display.type == FavouriteContentType.NOVEL
		// A query change shrinks databaseWindow before Room necessarily returns the smaller list. Limit
		// the stale snapshot here too, so a new keystroke never scans a previously loaded 16k list once.
		val currentWindow = databaseWindow.value
		val candidates = if (currentWindow == Int.MAX_VALUE || list.size <= currentWindow) {
			list
		} else {
			list.take(currentWindow)
		}
		val typed = candidates.filter { it.source.isNovelSource == wantNovel }
		val searched = searchMatcher.filter(typed, display.query)
		maybeExpandDatabaseWindow(
			loadedCount = candidates.size,
			matchingCount = searched.size,
			targetCount = display.limit,
		)
		// Search is global within the category, but rendering is progressive. A broad query such as
		// "a" can match thousands of titles; only the requested page is mapped to cards/read counters.
		val visible = searched.take(display.limit)
		visible.mapList(
			display.options.listMode,
			filters,
			pinned.takeIfDefaultState(filters),
			scalingTip,
			display.query.isNotBlank(),
			display.options,
		)
	}.distinctUntilChanged().onEach {
		isPaginationReady.set(true)
	}.catch {
		emit(listOf(it.toErrorState(canRetry = false)))
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	override fun onRefresh() {
		refreshTrigger.value = Any()
	}

	override fun onRetry() = Unit

	override fun setFilterOption(option: ListFilterOption, isApplied: Boolean) =
		quickFilter.setFilterOption(option, isApplied)

	override fun toggleFilterOption(option: ListFilterOption) = quickFilter.toggleFilterOption(option)

	override fun clearFilter() = quickFilter.clearFilter()

	fun dismissScalingTip() {
		settings.closeTip(TIP_UI_SCALING)
	}

	fun markAsRead(items: Set<Manga>) {
		launchLoadingJob(Dispatchers.Default) {
			markAsReadUseCase(items)
			onRefresh()
		}
	}

	fun removeFromFavourites(ids: Set<Long>) {
		if (ids.isEmpty()) return
		launchJob(Dispatchers.Default) {
			val handle = if (categoryId == NO_ID) {
				repository.removeFromFavourites(ids)
			} else {
				repository.removeFromCategory(categoryId, ids)
			}
			onActionDone.call(ReversibleAction(R.string.removed_from_favourites, handle))
		}
	}

	fun requestMoreItems() {
		if (!isPaginationReady.compareAndSet(true, false)) return
		val nextLimit = limit.value + PAGE_SIZE
		limit.value = nextLimit
		val preferredWindow = (nextLimit * 4).coerceAtMost(DATABASE_WINDOW_MAX)
		if (databaseWindow.value < preferredWindow) {
			databaseWindow.value = preferredWindow
		}
	}

	private suspend fun List<Manga>.mapList(
		mode: ListMode,
		filters: Set<ListFilterOption>,
		pinned: List<Long>,
		isScalingTipVisible: Boolean,
		isSearchActive: Boolean,
		display: FavouriteDisplayPreferences.Options,
	): List<ListModel> {
		if (isEmpty()) {
			if (isSearchActive) {
				return listOfNotNull(
					quickFilter.filterItem(filters),
					EmptyState(
						icon = R.drawable.ic_empty_favourites,
						textPrimary = R.string.nothing_found,
						textSecondary = R.string.text_empty_holder_secondary_filtered,
						actionStringRes = 0,
					),
				)
			}
			return if (filters.isEmpty()) {
				listOf(getEmptyState(false))
			} else {
				listOfNotNull(quickFilter.filterItem(filters), getEmptyState(true))
			}
		}
		val result = ArrayList<ListModel>(size + 2)
		if (isScalingTipVisible) result += uiScalingTip
		quickFilter.filterItem(filters)?.let(result::add)
		mangaListMapper.toListModelList(result, this, mode, MangaListMapper.NO_FAVORITE)
		val pinnedSet = pinned.toSet()
		for (i in result.indices) {
			val model = result[i]
			if (model !is MangaListModel) continue
			val isPinned = model.manga.id in pinnedSet
			val source = model.manga.source
			val isSaved = display.showDownloaded && model.manga.id in localMangaIndex
			val isLocalSource = display.showLocalSource && source.isLocal
			val languageLabel = if (display.showLanguage) source.getLanguageCode() else null
			val unreadCount = if (display.showUnread) unreadCounter.getUnreadCount(model.manga.id) else 0
			val hasReadingHistory = display.showContinueReading && historyRepository.getOne(model.manga) != null
			result[i] = when (model) {
				is MangaGridModel -> model.copy(
					counter = unreadCount,
					isSaved = isSaved,
					isPinned = isPinned,
					isTitleOverCover = display.titleOverCover,
					isGridSpacingIncreased = display.gridSpacingIncreased,
					isLocalSource = isLocalSource,
					languageLabel = languageLabel,
					showContinueReading = hasReadingHistory,
				)
				is MangaDetailedListModel -> model.copy(
					counter = unreadCount,
					isSaved = isSaved,
					isPinned = isPinned,
					isLocalSource = isLocalSource,
					languageLabel = languageLabel,
					showContinueReading = hasReadingHistory,
				)
				is MangaCompactListModel -> model.copy(
					counter = unreadCount,
					isPinned = isPinned,
					isSaved = isSaved,
					isLocalSource = isLocalSource,
					languageLabel = languageLabel,
					showContinueReading = hasReadingHistory,
				)
			}
		}
		return result
	}

	fun setPinned(ids: Set<Long>, isPinned: Boolean) {
		val current = settings.getPinnedFavourites(categoryId)
		val updated = if (isPinned) current + (ids - current.toSet()) else current - ids
		settings.setPinnedFavourites(categoryId, updated)
	}

	private fun observeFavorites() = combine(
		sortOrder.filterNotNull(),
		quickFilter.appliedOptions.combineWithSettings(),
		pinnedIds,
		databaseWindow,
		contentTypeStore.selectedType,
	) { order, filters, pinned, queryLimit, contentType ->
		val configurationChanged =
			(lastSortOrder != null && lastSortOrder != order) ||
				(lastFilters != null && lastFilters != filters) ||
				(lastContentType != null && lastContentType != contentType)
		lastSortOrder = order
		lastFilters = filters
		lastContentType = contentType

		val effectiveLimit = if (configurationChanged && queryLimit != Int.MAX_VALUE) {
			limit.value = PAGE_SIZE
			databaseWindow.value = DATABASE_WINDOW_INITIAL
			DATABASE_WINDOW_INITIAL
		} else {
			queryLimit
		}
		isPaginationReady.set(false)
		val effectivePinned = pinned.takeIfDefaultState(filters)
		if (categoryId == NO_ID) {
			repository.observeAll(order, filters, effectiveLimit, effectivePinned)
		} else {
			repository.observeAll(categoryId, order, filters, effectiveLimit, effectivePinned)
		}
	}.flattenLatest()

	private fun maybeExpandDatabaseWindow(
		loadedCount: Int,
		matchingCount: Int,
		targetCount: Int,
	) {
		if (matchingCount >= targetCount) return
		val current = databaseWindow.value
		if (loadedCount < current || current == Int.MAX_VALUE) return
		val next = if (current >= DATABASE_WINDOW_MAX) {
			Int.MAX_VALUE
		} else {
			(current * 2).coerceAtMost(DATABASE_WINDOW_MAX)
		}
		if (next != current) databaseWindow.value = next
	}

	private fun List<Long>.takeIfDefaultState(filters: Set<ListFilterOption>): List<Long> =
		if (filters.all { it == ListFilterOption.SFW }) this else emptyList()

	private fun getEmptyState(hasFilters: Boolean) = if (hasFilters) {
		EmptyState(
			icon = R.drawable.ic_empty_favourites,
			textPrimary = R.string.nothing_found,
			textSecondary = R.string.text_empty_holder_secondary_filtered,
			actionStringRes = R.string.reset_filter,
		)
	} else {
		EmptyState(
			icon = R.drawable.ic_empty_favourites,
			textPrimary = R.string.text_empty_holder_primary,
			textSecondary = if (categoryId == NO_ID) R.string.you_have_not_favourites_yet else R.string.favourites_category_empty,
			actionStringRes = 0,
		)
	}

	private data class DisplayState(
		val query: String,
		val type: FavouriteContentType,
		val limit: Int,
		val options: FavouriteDisplayPreferences.Options,
	)
}
