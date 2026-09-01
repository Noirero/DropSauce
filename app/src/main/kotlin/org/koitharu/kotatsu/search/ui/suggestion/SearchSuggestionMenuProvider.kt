package org.koitharu.kotatsu.search.ui.suggestion

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import com.google.android.material.search.SearchView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.setOptionalIconsVisibleCompat
import org.koitharu.kotatsu.search.domain.sanitizeSearchQuery

class SearchSuggestionMenuProvider(
	private val context: Context,
	private val viewModel: SearchSuggestionViewModel,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_search_suggestion, menu)
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		return when (menuItem.itemId) {
			R.id.action_paste_search -> {
				pasteSearchQuery()
				true
			}

			R.id.action_clear -> {
				clearSearchHistory()
				true
			}

			else -> false
		}
	}

	override fun onPrepareMenu(menu: Menu) {
		super.onPrepareMenu(menu)
		menu.setOptionalIconsVisibleCompat(true)
	}

	private fun pasteSearchQuery() {
		val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
		val clip = clipboard.primaryClip ?: return
		if (clip.itemCount == 0) return
		val query = sanitizeSearchQuery(clip.getItemAt(0).coerceToText(context)?.toString().orEmpty())
		if (query.isBlank()) return
		val searchView = (context as? Activity)?.findViewById<SearchView>(R.id.search_view) ?: return
		searchView.setText(query)
		viewModel.onQueryChanged(query)
	}

	private fun clearSearchHistory() {
		buildAlertDialog(context, isCentered = true) {
			setTitle(R.string.clear_search_history)
			setIcon(R.drawable.ic_clear_all)
			setCancelable(true)
			setMessage(R.string.text_clear_search_history_prompt)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.clear) { _, _ -> viewModel.clearSearchHistory() }
		}.show()
	}
}
