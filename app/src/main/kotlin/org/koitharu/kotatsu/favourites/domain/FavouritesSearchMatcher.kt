package org.koitharu.kotatsu.favourites.domain

import dagger.Reusable
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.details.data.MangaNotesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

/** Matches Favourites library items against user-visible metadata plus local Notes. */
@Reusable
class FavouritesSearchMatcher @Inject constructor(
	private val mangaDataRepository: MangaDataRepository,
	private val notesRepository: MangaNotesRepository,
) {

	suspend fun filter(items: List<Manga>, rawQuery: String): List<Manga> {
		val query = rawQuery.trim()
		if (query.isEmpty() || items.isEmpty()) return items
		val overrides = mangaDataRepository.getOverrides()
		return items.filter { manga ->
			val override = overrides[manga.id]
			manga.title.contains(query, ignoreCase = true) ||
				override?.title?.contains(query, ignoreCase = true) == true ||
				manga.authors.any { it.contains(query, ignoreCase = true) } ||
				override?.author?.contains(query, ignoreCase = true) == true ||
				override?.artist?.contains(query, ignoreCase = true) == true ||
				notesRepository.get(manga.id)?.contains(query, ignoreCase = true) == true
		}
	}
}
