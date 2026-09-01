package org.koitharu.kotatsu.alternatives.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

		// Keep source and detail requests bounded while allowing multiple genuinely different
		// matches from the same extension to survive (for example Chinese and Korean originals).
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

					// SearchV2Helper already sorts by title relevance. Inspect only the strongest matches.
					// Dedupe only exact source-item/title duplicates: entries with the same id but a different
					// displayed/original title remain separate so language variants are not collapsed.
					val candidates = list
						?.asSequence()
						?.filter { it.id != manga.id }
						?.distinctBy { it.dedupeKey() }
						?.take(MAX_DETAIL_CANDIDATES)
						?.toList()
					if (candidates.isNullOrEmpty()) {
						return@launch
					}

					val detailed = candidates.map { candidate ->
						async {
							detailsSemaphore.withPermit {
								runCatchingCancellable {
									mangaRepositoryFactory.create(candidate.source).getDetails(candidate)
								}.getOrDefault(candidate)
							}
						}
					}.awaitAll().distinctBy { it.dedupeKey() }

					for (result in detailed) {
						send(result)
					}
				}
			}
		}
	}

	private fun Manga.dedupeKey(): Pair<Long, String> = id to title.trim().lowercase()

	private fun getSources(scope: AlternativeSearchScope): List<MangaSource> = when (scope) {
		AlternativeSearchScope.PINNED -> sourcesRepository.getPinnedSources().toList()
		AlternativeSearchScope.ALL_INSTALLED -> sourcesRepository.getEnabledSources()
	}
}
