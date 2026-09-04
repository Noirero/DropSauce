package org.koitharu.kotatsu.favourites.domain

import dagger.Reusable
import org.koitharu.kotatsu.core.db.MangaDatabase
import javax.inject.Inject

/** Lightweight classifier for LOCAL items that belong to the virtual Downloaded novel shelf. */
@Reusable
class DownloadedContentClassifier @Inject constructor(
	private val db: MangaDatabase,
) {

	suspend fun getLocalNovelIds(): Set<Long> {
		val result = HashSet<Long>()
		for (entry in db.getLocalMangaIndexDao().findAllEntries()) {
			val path = entry.path.replace('\\', '/')
			if (!path.contains("/downloads/", ignoreCase = true)) continue
			val cleanPath = path.substringBefore('#').substringBefore('?')
			if (
				path.contains("/00.Novel/", ignoreCase = true) ||
				cleanPath.endsWith(".epub", ignoreCase = true)
			) {
				result += entry.mangaId
			}
		}
		return result
	}
}
