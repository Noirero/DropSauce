package org.koitharu.kotatsu.favourites.domain

import dagger.Reusable
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.data.toMangaHistory
import org.koitharu.kotatsu.list.domain.ReadingProgress
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

	data class Snapshot internal constructor(
		private val histories: Map<Long, HistoryEntity>,
		val unreadCounts: Map<Long, Int>,
	) {
		fun hasHistory(mangaId: Long): Boolean = mangaId in histories

		fun getHistory(mangaId: Long) = histories[mangaId]?.toMangaHistory()

		fun getProgress(mangaId: Long, mode: ProgressIndicatorMode): ReadingProgress? {
			val history = histories[mangaId] ?: return null
			val fixedPercent = if (ReadingProgress.isCompleted(history.percent)) 1f else history.percent
			return ReadingProgress(
				percent = fixedPercent,
				totalChapters = history.chaptersCount,
				mode = mode,
			).takeIf { it.isValid() }
		}
	}

	/**
	 * Loads the card metadata for a whole visible Favourites page in at most two Room queries instead
	 * of querying history/chapters again for every card.
	 */
	suspend fun getSnapshot(mangaIds: Collection<Long>, includeUnread: Boolean): Snapshot {
		if (mangaIds.isEmpty()) return Snapshot(emptyMap(), emptyMap())
		val ids = mangaIds.distinct()
		val histories = database.getHistoryDao().findByIds(ids).associateBy { it.mangaId }
		if (!includeUnread) return Snapshot(histories, emptyMap())

		val chaptersByManga = database.getChaptersDao().findAll(ids).groupBy { it.mangaId }
		val unread = HashMap<Long, Int>(chaptersByManga.size)
		for ((mangaId, chapters) in chaptersByManga) {
			unread[mangaId] = calculateUnreadCount(chapters, histories[mangaId])
		}
		return Snapshot(histories, unread)
	}

	suspend fun getUnreadCount(mangaId: Long): Int =
		getSnapshot(listOf(mangaId), includeUnread = true).unreadCounts[mangaId] ?: 0

	private fun calculateUnreadCount(chapters: List<ChapterEntity>, history: HistoryEntity?): Int {
		if (chapters.isEmpty()) return 0
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
