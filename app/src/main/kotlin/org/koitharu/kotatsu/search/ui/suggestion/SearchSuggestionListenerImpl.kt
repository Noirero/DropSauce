package org.koitharu.kotatsu.search.ui.suggestion

import android.text.Editable
import android.view.KeyEvent
import android.widget.TextView
import androidx.core.net.toUri
import com.google.android.material.search.SearchView
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaLinkResolver
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.sanitizeSearchQuery

class SearchSuggestionListenerImpl(
	private val router: AppRouter,
	private val searchView: SearchView,
	private val viewModel: SearchSuggestionViewModel,
) : SearchSuggestionListener {

	override fun onMangaClick(manga: Manga) {
		router.openDetails(manga)
	}

	override fun onQueryClick(query: String, kind: SearchKind, submit: Boolean) {
		val cleanQuery = sanitizeSearchQuery(query)
		if (submit && cleanQuery.isNotEmpty()) {
			if (viewModel.isFavouritesSearchScope) {
				// In Favourites the live result list is the search result. Never leave the library and
				// fan the same query out to every installed extension.
				searchView.setText(cleanQuery)
				viewModel.onQueryChanged(cleanQuery)
				return
			}
			if (kind == SearchKind.SIMPLE && MangaLinkResolver.isValidLink(cleanQuery)) {
				router.openDetails(cleanQuery.toUri())
			} else {
				router.openSearch(cleanQuery, kind)
				if (kind != SearchKind.TAG) {
					viewModel.saveQuery(cleanQuery)
				}
			}
			// Deliberately left open: coming back from the results lands on the search bar again.
		} else {
			searchView.setText(cleanQuery)
		}
	}

	override fun onTagClick(tag: MangaTag) {
		router.openSearch(tag.title, SearchKind.TAG)
	}

	override fun onSourceClick(source: MangaSource) {
		router.openList(source, null, null)
	}

	override fun onSourceSettingsClick(source: MangaSource) {
		router.openSourceSettings(source)
	}

	override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

	override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

	override fun afterTextChanged(s: Editable?) {
		viewModel.onQueryChanged(s?.toString().orEmpty())
	}

	override fun onEditorAction(
		v: TextView?,
		actionId: Int,
		event: KeyEvent?
	): Boolean {
		val query = sanitizeSearchQuery(v?.text?.toString().orEmpty())
		if (query.isEmpty()) {
			return false
		}
		onQueryClick(query, SearchKind.SIMPLE, true)
		return true
	}
}
