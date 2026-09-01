package org.koitharu.kotatsu.alternatives.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.AlternativeSearchEvent
import org.koitharu.kotatsu.alternatives.domain.AlternativesUseCase
import org.koitharu.kotatsu.alternatives.domain.MigrateUseCase
import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingFooter
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrDefault
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.search.domain.LANGUAGE_OTHER
import org.koitharu.kotatsu.search.domain.SearchSourceMode
import org.koitharu.kotatsu.search.domain.SearchSourcePreferences
import org.koitharu.kotatsu.search.domain.searchLanguageCode
import java.util.Locale
import javax.inject.Inject

data class AlternativeSearchProgress(
	val completed: Int = 0,
	val total: Int = 0,
	val errors: Int = 0,
)

private data class AlternativeSourceStatus(
	val loading: Boolean = true,
	val error: Throwable? = null,
)

@HiltViewModel
class AlternativesViewModel @Inject constructor(
	private val savedStateHandle: SavedStateHandle,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val alternativesUseCase: AlternativesUseCase,
	private val migrateUseCase: MigrateUseCase,
	private val mangaListMapper: MangaListMapper,
	private val searchPreferences: SearchSourcePreferences,
	@ApplicationContext private val context: Context,
) : BaseViewModel() {

	val manga = savedStateHandle.require<ParcelableManga>(AppRouter.KEY_MANGA).manga
	val hasPinnedSources: Boolean = alternativesUseCase.hasPinnedSources()

	private val results = MutableStateFlow<List<MangaAlternativeModel>>(emptyList())
	private val sourceOrder = MutableStateFlow<List<MangaSource>>(emptyList())
	private val sourceStatuses = MutableStateFlow<Map<MangaSource, AlternativeSourceStatus>>(emptyMap())
	private val selectedLanguages = MutableStateFlow(searchPreferences.preferredLanguages)
	val preferredLanguages: StateFlow<Set<String>> = selectedLanguages
	private val availableLanguagesState = MutableStateFlow(alternativesUseCase.getAvailableLanguages(manga))
	val availableLanguages: StateFlow<List<String>> = availableLanguagesState
	private val hasResultsOnlyState = MutableStateFlow(searchPreferences.alternativeHasResultsOnly)
	val hasResultsOnly: StateFlow<Boolean> = hasResultsOnlyState

	private val selectedMode = MutableStateFlow(resolveInitialMode())
	val searchMode: StateFlow<SearchSourceMode> = selectedMode

	private val progressState = MutableStateFlow(AlternativeSearchProgress())
	val searchProgress: StateFlow<AlternativeSearchProgress> = progressState

	private var migrationJob: Job? = null
	private var searchJob: Job? = null

	private val mangaDetails = suspendLazy {
		mangaRepositoryFactory.create(manga.source).getDetails(manga)
	}

	val onMigrated = MutableEventFlow<Manga>()

	val list: StateFlow<List<ListModel>> = combine(
		results,
		sourceOrder,
		sourceStatuses,
		isLoading,
		hasResultsOnlyState,
	) { alternatives, sources, statuses, loading, hasResultsOnly ->
		val content = ArrayList<ListModel>()
		for (source in sources) {
			val sourceResults = alternatives.filter { it.manga.source == source }
			val status = statuses[source] ?: AlternativeSourceStatus(loading = false)
			val shouldShow = sourceResults.isNotEmpty() || status.loading || status.error != null || !hasResultsOnly
			if (!shouldShow) continue

			val language = source.searchLanguageCode().let { code ->
				if (code == LANGUAGE_OTHER) null else code.uppercase(Locale.ROOT)
			}
			val state = when {
				status.loading -> "…"
				status.error != null -> "⚠"
				sourceResults.isNotEmpty() -> "✓"
				else -> "0"
			}
			val header = listOfNotNull(
				source.getTitle(context),
				language,
				if (status.loading) null else sourceResults.size.toString(),
				state,
			).joinToString(" · ")
			content += ListHeader(header)
			content += sourceResults
		}

		when {
			content.isEmpty() -> listOf(
				when {
					loading -> LoadingState
					else -> EmptyState(
						icon = R.drawable.ic_empty_common,
						textPrimary = R.string.nothing_found,
						textSecondary = R.string.text_search_holder_secondary,
						actionStringRes = 0,
					)
				},
			)
			loading -> content + LoadingFooter()
			else -> content
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		doSearch()
	}

	fun retry() {
		searchJob?.cancel()
		doSearch()
	}

	fun setSearchMode(mode: SearchSourceMode) {
		val resolved = if (mode == SearchSourceMode.PINNED_ONLY && !hasPinnedSources) {
			SearchSourceMode.PREFERRED_LANGUAGES
		} else mode
		if (selectedMode.value == resolved) return
		searchPreferences.alternativeMode = resolved
		savedStateHandle[STATE_SEARCH_MODE] = resolved.name
		selectedMode.value = resolved
		doSearch()
	}

	fun setPreferredLanguages(languages: Set<String>) {
		val normalized = languages.ifEmpty { searchPreferences.defaultPreferredLanguages }
		if (selectedLanguages.value == normalized) return
		searchPreferences.preferredLanguages = normalized
		selectedLanguages.value = normalized
		doSearch()
	}

	fun setHasResultsOnly(value: Boolean) {
		if (hasResultsOnlyState.value == value) return
		searchPreferences.alternativeHasResultsOnly = value
		hasResultsOnlyState.value = value
	}

	fun resetFilters() {
		searchPreferences.resetAlternative()
		selectedLanguages.value = searchPreferences.preferredLanguages
		val mode = SearchSourceMode.PREFERRED_LANGUAGES
		searchPreferences.alternativeMode = mode
		savedStateHandle[STATE_SEARCH_MODE] = mode.name
		selectedMode.value = mode
		hasResultsOnlyState.value = true
		doSearch()
	}

	fun migrate(target: Manga) {
		if (migrationJob?.isActive == true) return
		migrationJob = launchLoadingJob(Dispatchers.Default) {
			migrateUseCase(manga, target)
			onMigrated.call(target)
		}
	}

	private fun doSearch() {
		val prevJob = searchJob
		searchJob = launchLoadingJob(Dispatchers.Default) {
			prevJob?.cancelAndJoin()
			results.value = emptyList()
			sourceStatuses.value = emptyMap()
			progressState.value = AlternativeSearchProgress()

			val ref = mangaDetails.getOrDefault(manga)
			val refCount = ref.chaptersCount()
			availableLanguagesState.value = alternativesUseCase.getAvailableLanguages(ref)
			val sources = alternativesUseCase.getSources(ref, selectedMode.value, selectedLanguages.value)
			sourceOrder.value = sources
			sourceStatuses.value = sources.associateWith { AlternativeSourceStatus() }
			progressState.value = AlternativeSearchProgress(total = sources.size)

			alternativesUseCase(ref, selectedMode.value, selectedLanguages.value).collect { event ->
				when (event) {
					is AlternativeSearchEvent.Result -> {
						val model = MangaAlternativeModel(
							mangaModel = mangaListMapper.toListModel(event.manga, ListMode.GRID) as MangaGridModel,
							referenceChapters = refCount,
						)
						upsertResult(model)
					}
					is AlternativeSearchEvent.SourceFinished -> {
						sourceStatuses.update { current ->
							current + (event.source to AlternativeSourceStatus(loading = false, error = event.error))
						}
						progressState.update { current ->
							current.copy(
								completed = (current.completed + 1).coerceAtMost(current.total),
								errors = current.errors + if (event.error != null) 1 else 0,
							)
						}
					}
				}
			}
		}
	}

	private fun upsertResult(model: MangaAlternativeModel) {
		results.update { current ->
			val key = model.resultKey()
			val index = current.indexOfFirst { it.resultKey() == key }
			val updated = if (index == -1) {
				current + model
			} else {
				current.toMutableList().also { it[index] = model }
			}
			val sourceRanks = sourceOrder.value.withIndex().associate { (rank, source) -> source to rank }
			updated.sortedWith(
				compareBy<MangaAlternativeModel>(
					{ sourceRanks[it.manga.source] ?: Int.MAX_VALUE },
					{ it.manga.title.levenshteinDistance(manga.title) },
				).thenByDescending { it.chaptersCount },
			)
		}
	}

	private fun MangaAlternativeModel.resultKey(): Triple<String, Long, String> = Triple(
		manga.source.name,
		manga.id,
		manga.title.trim().lowercase(),
	)

	private fun resolveInitialMode(): SearchSourceMode {
		val saved = savedStateHandle.get<String>(STATE_SEARCH_MODE)
			?.let { raw -> SearchSourceMode.entries.firstOrNull { it.name == raw } }
		val preferred = saved ?: searchPreferences.alternativeMode
		return if (preferred == SearchSourceMode.PINNED_ONLY && !hasPinnedSources) {
			SearchSourceMode.PREFERRED_LANGUAGES
		} else preferred
	}

	private companion object {
		const val STATE_SEARCH_MODE = "alternative_search_mode"
	}
}
