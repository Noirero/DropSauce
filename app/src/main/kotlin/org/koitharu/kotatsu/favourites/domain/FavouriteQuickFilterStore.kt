package org.koitharu.kotatsu.favourites.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.list.domain.ListFilterOption
import javax.inject.Inject
import javax.inject.Singleton

/** Session-wide non-source quick filters for Favourites category pages. */
@Singleton
class FavouriteQuickFilterStore @Inject constructor(
	networkState: NetworkState,
) {

	private val mutableState = MutableStateFlow<Set<ListFilterOption>>(
		if (networkState.value) emptySet() else setOf(ListFilterOption.Downloaded),
	)
	val state: StateFlow<Set<ListFilterOption>> = mutableState.asStateFlow()

	fun set(option: ListFilterOption, isSelected: Boolean) {
		mutableState.update { current ->
			if (!isSelected) {
				current - option
			} else {
				current.filterNot { it.groupKey == option.groupKey }.toSet() + option
			}
		}
	}

	fun toggle(option: ListFilterOption) {
		set(option, option !in mutableState.value)
	}

	fun clear() {
		mutableState.value = emptySet()
	}
}
