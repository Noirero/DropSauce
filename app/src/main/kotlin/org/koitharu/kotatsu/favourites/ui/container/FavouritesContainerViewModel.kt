package org.koitharu.kotatsu.favourites.ui.container

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
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
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesSearchMatcher
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment.Companion.NO_ID
import javax.inject.Inject

@HiltViewModel
class FavouritesContainerViewModel @Inject constructor(
	private val settings: AppSettings,
	private val favouritesRepository: FavouritesRepository,
	private val searchMatcher: FavouritesSearchMatcher,
	private val contentTypeStore: FavouriteContentTypeStore,
) : BaseViewModel() {

	val onActionDone = MutableEventFlow<ReversibleAction>()

	private val categoriesStateFlow = favouritesRepository.observeCategoriesForLibrary()
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val categories = combine(
		categoriesStateFlow.filterNotNull(),
		observeAllFavouritesVisibility(),
		favouritesRepository.observeCategoriesWithCovers(),
		contentTypeStore.selectedType,
		FavouritesContainerFragment.searchQuery,
	) { list, showAll, _, type, query ->
		val typedCategories = list.filter { contentTypeStore.isCategoryForType(it.id, type) }
		val wantNovel = type == FavouriteContentType.NOVEL
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
		typedCategories.toUi(showAll, matchingIds.size, counts)
	}.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val isEmpty = categories.map { it.isEmpty() }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	private fun List<FavouriteCategory>.toUi(
		showAll: Boolean,
		allCount: Int,
		counts: Map<Long, Int>,
	): List<FavouriteTabModel> {
		if (isEmpty() && !showAll) return emptyList()
		val result = ArrayList<FavouriteTabModel>(if (showAll) size + 1 else size)
		if (showAll) {
			result.add(FavouriteTabModel(NO_ID, null, allCount))
		}
		mapTo(result) { FavouriteTabModel(it.id, it.title, counts[it.id] ?: 0) }
		return result
	}

	fun hide(categoryId: Long) {
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
		launchJob(Dispatchers.Default) {
			favouritesRepository.removeCategories(setOf(categoryId))
			contentTypeStore.removeCategories(setOf(categoryId))
		}
	}

	private fun observeAllFavouritesVisibility() = settings.observeAsFlow(
		key = AppSettings.KEY_ALL_FAVOURITES_VISIBLE,
		valueProducer = { isAllFavouritesVisible },
	)
}
