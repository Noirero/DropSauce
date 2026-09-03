package org.koitharu.kotatsu.favourites.domain

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.MangaListQuickFilter
import org.koitharu.kotatsu.list.ui.model.ExtensionFilter
import org.koitharu.kotatsu.mihon.MihonExtensionManager

class FavoritesListQuickFilter @AssistedInject constructor(
	@Assisted private val categoryId: Long,
	private val settings: AppSettings,
	private val repository: FavouritesRepository,
	networkState: NetworkState,
	private val mihonExtensionManager: MihonExtensionManager,
) : MangaListQuickFilter(settings) {

	private var didRefreshExtensions = false

	init {
		setFilterOption(ListFilterOption.Downloaded, !networkState.value)
	}

	override suspend fun getAvailableFilterOptions(): List<ListFilterOption> = buildList {
		add(ListFilterOption.Downloaded)
		if (settings.isTrackerEnabled) {
			add(ListFilterOption.Macro.NEW_CHAPTERS)
		}
		add(ListFilterOption.Macro.COMPLETED)
	}

	override suspend fun getAdditionalChips(
		selectedOptions: Set<ListFilterOption>,
	): List<ChipsView.ChipModel> {
		val options = getSourceOptions()
		val selectedSources = selectedOptions.filterIsInstance<ListFilterOption.Source>().toSet()
		if (options.isEmpty() && selectedSources.isEmpty()) {
			return emptyList()
		}
		return listOf(
			ChipsView.ChipModel(
				icon = R.drawable.ic_filter_funnel,
				isChecked = selectedSources.isNotEmpty(),
				isCheckedIconVisible = false,
				isIconOnly = true,
				data = ExtensionFilter(
					options = options,
					selectedOptions = selectedSources,
				),
			),
		)
	}

	private suspend fun getSourceOptions(): List<ListFilterOption.Source> {
		// An empty category has no source filter to resolve. Checking the lightweight database list
		// first lets its empty state render immediately instead of waiting for extension discovery.
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
