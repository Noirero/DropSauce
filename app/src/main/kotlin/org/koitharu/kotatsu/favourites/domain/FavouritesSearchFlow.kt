package org.koitharu.kotatsu.favourites.domain

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val FAVOURITES_SEARCH_DEBOUNCE_MS = 250L

/**
 * Shared search-input policy for the Favourites container and its pages.
 *
 * The raw EditText state remains immediate, but expensive filtering/counting waits briefly while the
 * user is still typing. Clearing the query is immediate so leaving search never leaves stale results
 * visible. Trimming here also prevents whitespace-only edits from re-running a large-library search.
 */
@OptIn(FlowPreview::class)
fun Flow<String>.debounceFavouritesSearch(): Flow<String> =
	map(String::trim)
		.debounce { query -> if (query.isEmpty()) 0L else FAVOURITES_SEARCH_DEBOUNCE_MS }
		.distinctUntilChanged()
