package org.koitharu.kotatsu.alternatives.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchV2Helper
import javax.inject.Inject

private const val MAX_PARALLEL_SOURCES = 5
private const val MAX_PARALLEL_DETAILS = 5
private const val MAX_DETAIL_CANDIDATES = 3

enum class AlternativeSearchScope {
	PINNED,
	ALL_INSTALLED,
}

class AlternativesUseCase @Inject constructor(
	private val sourcesRepository: MangaSourcesRepository,
	private val searchHelperFactory: SearchV2Helper.Factory,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {

	fun hasPinnedSources(): Boolean = sourcesRepository.getPinnedSources().isNotEmpty()

	fun defaultScope(): AlternativeSearchScope = if (hasPinnedSources()) {
		AlternativeSearchScope.PINNED
	} else {
		AlternativeSearchScope.ALL_INSTALLED
	}

	suspend operator fun invoke(
		manga: Manga,
		scope: AlternativeSearchScope = defaultScope(),
	): Flow<Manga> {
		// Same kind only. A manga migrated onto a novel source (or the reverse) lands in the wrong
		// reader with content it cannot load, so those are never offered as alternatives.
		val isNovel = manga.source.isNovelSource
		val sources = getSources(scope).filter { it != manga.source && it.isNovelSource == isNovel }
		if (sources.isEmpty()) {
			return emptyFlow()
		}

		// Kahon uses five parallel migration sources. Keep source searches responsive while also
		// capping detail requests separately so a source returning many matches cannot flood the
		// network/client and make the screen feel slower instead of faster.
		val sourceSemaphore = Semaphore(MAX_PARALLEL_SOURCES)
		val detailsSemaphore = Semaphore(MAX_PARALLEL_DETAILS)
		return channelFlow {
			for (source in sources) {
				launch {
					val searchHelper = searchHelperFactory.create(source)
					val list = runCatchingCancellable {
						sourceSemaphore.withPermit {
							searchHelper(manga.title, SearchKind.TITLE)?.manga
						}
					}.getOrNull()

					// SearchV2Helper already sorts by title relevance. Inspect only the strongest matches
					// instead of fetching details for every result a source returns. This keeps the existing
					// "prefer the branch with more chapters" behaviour without generating dozens of detail
					// requests for weakly related titles.
					val candidates = list
						?.asSequence()
						?.filter { it.id != manga.id }
						?.distinctBy { it.id }
						?.take(MAX_DETAIL_CANDIDATES)
						?.toList()
					if (candidates.isNullOrEmpty()) {
						return@launch
					}

					val best = candidates.map { candidate ->
						async {
							detailsSemaphore.withPermit {
								runCatchingCancellable {
									mangaRepositoryFactory.create(candidate.source).getDetails(candidate)
								}.getOrDefault(candidate)
							}
						}
					}.awaitAll().maxByOrNull { it.chaptersCount() }
					if (best != null) {
						send(best)
					}
				}
			}
		}
	}

	private fun getSources(scope: AlternativeSearchScope): List<MangaSource> = when (scope) {
		AlternativeSearchScope.PINNED -> sourcesRepository.getPinnedSources().toList()
		AlternativeSearchScope.ALL_INSTALLED -> sourcesRepository.getEnabledSources()
	}
}
