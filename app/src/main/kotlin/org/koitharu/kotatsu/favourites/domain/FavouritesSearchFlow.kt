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
 * The raw EditText state remains immediate, while expensive filtering/counting waits briefly as the
 * user types. Trimming and distinctUntilChanged prevent whitespace-only/repeated text from re-running
 * a large-library search.
 */
@OptIn(FlowPreview::class)
fun Flow<String>.debounceFavouritesSearch(): Flow<String> =
	map { it.trim() }
		.distinctUntilChanged()
		.debounce(FAVOURITES_SEARCH_DEBOUNCE_MS)
