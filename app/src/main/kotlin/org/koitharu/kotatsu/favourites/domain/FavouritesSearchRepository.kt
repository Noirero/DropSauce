package org.koitharu.kotatsu.favourites.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.favourites.data.FavouriteMembership
import org.koitharu.kotatsu.favourites.data.FavouriteSearchEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight cached metadata used by Favourites search/counting.
 *
 * A 16k+ library should not re-read the same lightweight rows from SQLite for every debounced
 * character. The container invalidates this cache only when Room reports a real favourites change.
 */
@Singleton
class FavouritesSearchRepository @Inject constructor(
	private val db: MangaDatabase,
) {
	private val mutex = Mutex()
	@Volatile private var generation = 0L
	@Volatile private var cachedEntries: List<FavouriteSearchEntry>? = null
	@Volatile private var cachedMemberships: List<FavouriteMembership>? = null

	fun invalidate() {
		generation++
		cachedEntries = null
		cachedMemberships = null
	}

	suspend fun getEntries(): List<FavouriteSearchEntry> {
		cachedEntries?.let { return it }
		return mutex.withLock {
			cachedEntries?.let { return@withLock it }
			val expectedGeneration = generation
			val loaded = db.getFavouritesDao().findSearchEntries()
			if (generation == expectedGeneration) cachedEntries = loaded
			loaded
		}
	}

	suspend fun getMemberships(): List<FavouriteMembership> {
		cachedMemberships?.let { return it }
		return mutex.withLock {
			cachedMemberships?.let { return@withLock it }
			val expectedGeneration = generation
			val loaded = db.getFavouritesDao().findMemberships()
			if (generation == expectedGeneration) cachedMemberships = loaded
			loaded
		}
	}
}
