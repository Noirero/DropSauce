package org.koitharu.kotatsu.favourites.domain

import dagger.Reusable
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import kotlin.math.ceil
import javax.inject.Inject

/**
 * Calculates the number shown by the Favourites "unread chapters" card option.
 *
 * Tracker counters only represent chapters discovered since the last tracker check, so they are
 * not suitable for an unread badge. This counter stays completely local: it uses the cached
 * chapter table plus reading history and never performs a source/network request.
 */
@Reusable
class FavouriteUnreadCounter @Inject constructor(
	private val database: MangaDatabase,
) {

	suspend fun getUnreadCount(mangaId: Long): Int {
		val chapters = database.getChaptersDao().findAll(mangaId)
		if (chapters.isEmpty()) return 0

		val history = database.getHistoryDao().find(mangaId)
		if (history == null) {
			// Multiple scanlator branches can contain the same logical chapter. Before the user has
			// selected/read a branch, use the largest branch instead of summing duplicate branches.
			return chapters.logicalChapterCount()
		}

		val current = chapters.firstOrNull { it.chapterId == history.chapterId }
		if (current != null) {
			val branchChapters = chapters.filter { it.branch == current.branch }
			val currentIndex = branchChapters.indexOfFirst { it.chapterId == history.chapterId }
			if (currentIndex >= 0) {
				// History progress in DropSauce advances chapter-by-chapter, so the current history
				// chapter is considered read and every chapter after it is unread.
				return (branchChapters.size - currentIndex - 1).coerceAtLeast(0)
			}
		}

		// Chapter ids can be re-keyed by an extension update. Fall back to the persisted progress
		// snapshot so a stale id does not suddenly mark the whole title unread.
		val total = history.chaptersCount.takeIf { it > 0 } ?: chapters.logicalChapterCount()
		if (total <= 0) return 0
		val read = ceil(history.percent.coerceIn(0f, 1f).toDouble() * total.toDouble())
			.toInt()
			.coerceIn(0, total)
		return (total - read).coerceAtLeast(0)
	}

	private fun List<ChapterEntity>.logicalChapterCount(): Int {
		if (isEmpty()) return 0
		return groupBy { it.branch }
			.maxOfOrNull { (_, items) -> items.size }
			?: 0
	}
}