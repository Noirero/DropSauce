package org.koitharu.kotatsu.search.domain

import android.os.SystemClock
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

private const val SEARCH_CACHE_TTL_MS = 90_000L
private const val SEARCH_CACHE_MAX_ENTRIES = 96

@Singleton
class SearchResultCache @Inject constructor() {

	private data class Entry(
		val createdAt: Long,
		val result: SearchResults,
	)

	private val cache = LinkedHashMap<String, Entry>(SEARCH_CACHE_MAX_ENTRIES, 0.75f, true)

	@Synchronized
	fun get(source: MangaSource, query: String, kind: SearchKind): SearchResults? {
		val key = key(source, query, kind)
		val entry = cache[key] ?: return null
		if (SystemClock.elapsedRealtime() - entry.createdAt > SEARCH_CACHE_TTL_MS) {
			cache.remove(key)
			return null
		}
		return entry.result
	}

	@Synchronized
	fun put(source: MangaSource, query: String, kind: SearchKind, result: SearchResults) {
		cache[key(source, query, kind)] = Entry(SystemClock.elapsedRealtime(), result)
		while (cache.size > SEARCH_CACHE_MAX_ENTRIES) {
			val oldestKey = cache.entries.firstOrNull()?.key ?: break
			cache.remove(oldestKey)
		}
	}

	private fun key(source: MangaSource, query: String, kind: SearchKind): String =
		"${source.name}\n${kind.name}\n${query.trim().lowercase()}"
}
