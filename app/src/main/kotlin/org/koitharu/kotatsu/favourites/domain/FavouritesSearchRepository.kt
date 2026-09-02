package org.koitharu.kotatsu.favourites.domain

import dagger.Reusable
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.favourites.data.FavouriteSearchEntry
import javax.inject.Inject

/** Lightweight metadata source dedicated to Favourites text search/counting. */
@Reusable
class FavouritesSearchRepository @Inject constructor(
	private val db: MangaDatabase,
) {
	suspend fun getEntries(): List<FavouriteSearchEntry> = db.getFavouritesDao().findSearchEntries()
}
