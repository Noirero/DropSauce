package org.koitharu.kotatsu.favourites.domain

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.MangaListQuickFilter
import org.koitharu.kotatsu.list.ui.model.ExtensionFilter
import org.koitharu.kotatsu.mihon.MihonExtensionManager

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class DownloadedShelfFilterState(
	private val delegate: StateFlow<Set<ListFilterOption>>,
) : StateFlow<Set<ListFilterOption>> by delegate {

	override val value: Set<ListFilterOption>
		get() = delegate.value - ListFilterOption.Downloaded

	override val replayCache: List<Set<ListFilterOption>>
		get() = listOf(value)

	override suspend fun collect(collector: FlowCollector<Set<ListFilterOption>>): Nothing =
		delegate.collect(
			FlowCollector { filters -> collector.emit(filters - ListFilterOption.Downloaded) },
		)
}

class FavoritesListQuickFilter @AssistedInject constructor(
	@Assisted private val categoryId: Long,
	private val settings: AppSettings,
	private val repository: FavouritesRepository,
	private val filterStore: FavouriteQuickFilterStore,
	networkState: NetworkState,
	private val mihonExtensionManager: MihonExtensionManager,
) : MangaListQuickFilter(settings) {

	private var didRefreshExtensions = false
	private val categoryAppliedOptions: StateFlow<Set<ListFilterOption>> =
		if (categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID) {
			DownloadedShelfFilterState(filterStore.state)
		} else {
			filterStore.state
		}

	init {
		isStateFilterEnabled = false
		if (!networkState.value) {
			filterStore.set(ListFilterOption.Downloaded, true)
		}
	}

	override val appliedOptions
		get() = categoryAppliedOptions

	override fun setFilterOption(option: ListFilterOption, isApplied: Boolean) {
		filterStore.set(option, isApplied)
	}

	override fun toggleFilterOption(option: ListFilterOption) {
		filterStore.toggle(option)
	}

	override fun clearFilter() {
		filterStore.clear()
	}

	override suspend fun getAvailableFilterOptions(): List<ListFilterOption> = emptyList()

	override suspend fun getAdditionalChips(
		selectedOptions: Set<ListFilterOption>,
	): List<ChipsView.ChipModel> = buildList {
		val progress = selectedOptions.filterIsInstance<ListFilterOption.ReadingProgress>().firstOrNull()
		val continueReading = ListFilterOption.ReadingProgress.IN_PROGRESS
		add(
			ChipsView.ChipModel(
				titleResId = R.string.favorites_continue_reading,
				isChecked = progress == continueReading,
				isCheckedIconVisible = false,
				data = continueReading,
			),
		)

		if (settings.isTrackerEnabled) {
			add(
				ChipsView.ChipModel(
					titleResId = R.string.favorites_new_chapters,
					icon = R.drawable.ic_updated,
					isChecked = ListFilterOption.Macro.NEW_CHAPTERS in selectedOptions,
					isCheckedIconVisible = false,
					data = ListFilterOption.Macro.NEW_CHAPTERS,
				),
			)
		}

		if (categoryId != DOWNLOADED_FAVOURITES_CATEGORY_ID) {
			add(
				ChipsView.ChipModel(
					titleResId = R.string.favorites_on_device,
					icon = R.drawable.ic_storage,
					isChecked = ListFilterOption.Downloaded in selectedOptions,
					isCheckedIconVisible = false,
					data = ListFilterOption.Downloaded,
				),
			)
		}

		val options = getSourceOptions()
		val selectedSources = selectedOptions.filterIsInstance<ListFilterOption.Source>().toSet()
		val publicationState = selectedOptions.filterIsInstance<ListFilterOption.State>().firstOrNull()
		val advancedCount =
			(if (selectedSources.isNotEmpty()) 1 else 0) +
				(if (publicationState != null) 1 else 0) +
				(if (progress != null && progress != continueReading) 1 else 0)
		add(
			ChipsView.ChipModel(
				titleResId = R.string.favorites_filter,
				icon = R.drawable.ic_filter_funnel,
				counter = advancedCount,
				isChecked = advancedCount > 0,
				isCheckedIconVisible = false,
				isDropdown = true,
				data = ExtensionFilter(
					options = options,
					selectedOptions = selectedSources,
					readingProgress = progress,
					publicationState = publicationState,
					isAdvanced = true,
				),
			),
		)
	}

	private suspend fun getSourceOptions(): List<ListFilterOption.Source> {
		val categorySources = repository.findSources(categoryId)
		if (categorySources.isEmpty()) return emptyList()

		mihonExtensionManager.ensureReady(forceRefresh = !didRefreshExtensions)
		didRefreshExtensions = true
		val installedSources = mihonExtensionManager.getMihonMangaSources()
		return categorySources.mapNotNull { source ->
			installedSources.firstOrNull { it == source }
		}.distinctBy {
			it.name
		}.map {
			ListFilterOption.Source(it)
		}
	}

	@AssistedFactory
	interface Factory {
		fun create(categoryId: Long): FavoritesListQuickFilter
	}
}
