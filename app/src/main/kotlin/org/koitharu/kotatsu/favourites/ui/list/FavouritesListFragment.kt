package org.koitharu.kotatsu.favourites.ui.list

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import coil3.request.ImageRequest
import coil3.size.Size
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.util.ext.mangaExtra
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.stableMangaCoverKey
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.core.util.ext.withArgs
import org.koitharu.kotatsu.databinding.FragmentListBinding
import org.koitharu.kotatsu.list.ui.MangaListFragment
import org.koitharu.kotatsu.list.ui.adapter.MangaListAdapter
import org.koitharu.kotatsu.list.ui.config.ListConfigSection
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.size.DynamicItemSizeResolver
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable

@AndroidEntryPoint
class FavouritesListFragment : MangaListFragment() {

	override val viewModel by viewModels<FavouritesListViewModel>()

	override val isSwipeRefreshEnabled = false
	override val paginationOffset = 12

	private val coverPrefetchSemaphore = Semaphore(3)
	private val prefetchedCovers = LinkedHashSet<String>()

	val categoryId
		get() = viewModel.categoryId

	override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.recyclerView.isVP2BugWorkaroundEnabled = true
		viewModel.gridScale.observe(viewLifecycleOwner) {
			val adapter = binding.recyclerView.adapter ?: return@observe
			val layoutManager = binding.recyclerView.layoutManager as? GridLayoutManager ?: return@observe
			val first = layoutManager.findFirstVisibleItemPosition()
			val last = layoutManager.findLastVisibleItemPosition()
			if (first >= 0 && last >= first && first < adapter.itemCount) {
				adapter.notifyItemRangeChanged(first, (last - first + 1).coerceAtMost(adapter.itemCount - first))
			}
		}
		viewModel.content.observe(viewLifecycleOwner, ::prefetchCovers)
	}

	override fun onResume() {
		super.onResume()
		prefetchCovers(viewModel.content.value)
	}

	private fun prefetchCovers(items: List<ListModel>) {
		if (!isResumed) return
		val columns = viewModel.gridColumns.value ?: 2
		val width = (resources.displayMetrics.widthPixels / columns.coerceAtLeast(1)).coerceAtLeast(120)
		val size = Size(width, width * 18 / 13)
		for (item in items.filterIsInstance<MangaListModel>().takeLast(COVER_PREFETCH_BATCH)) {
			val coverUrl = item.coverUrl ?: continue
			val key = "${item.id}:$coverUrl"
			if (!prefetchedCovers.add(key)) continue
			viewLifecycleScope.launch {
				coverPrefetchSemaphore.withPermit {
					val request = ImageRequest.Builder(requireContext())
						.data(coverUrl)
						.size(size)
						.mangaExtra(item.manga)
						.stableMangaCoverKey(item.manga, coverUrl)
						.build()
					runCatchingCancellable { coil.execute(request) }
				}
			}
		}
		while (prefetchedCovers.size > MAX_REMEMBERED_COVERS) {
			prefetchedCovers.remove(prefetchedCovers.first())
		}
	}

	override fun onCreateAdapter() = MangaListAdapter(
		listener = this,
		sizeResolver = DynamicItemSizeResolver(resources, viewLifecycleOwner, settings, adjustWidth = false),
		titleTapToRead = settings.isTitleTapToReadEnabled,
		onTipClose = { viewModel.dismissScalingTip() },
		gridVisualScaleProvider = { viewModel.gridScale.value },
	)

	override fun onScrolledToEnd() = viewModel.requestMoreItems()

	override fun onEmptyActionClick() = viewModel.clearFilter()

	override fun onFilterClick(view: View?) {
		router.showListSortSheet(ListConfigSection.Favorites(categoryId))
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu
	): Boolean {
		menuInflater.inflate(R.menu.mode_favourites, menu)
		return super.onCreateActionMode(controller, menuInflater, menu)
	}

	override fun onPrepareActionMode(controller: ListSelectionController, mode: ActionMode?, menu: Menu): Boolean {
		val pinned = viewModel.pinnedIds.value
		val ids = selectedItemsIds
		menu.findItem(R.id.action_pin)?.isVisible = ids.isNotEmpty() && ids.none { it in pinned }
		menu.findItem(R.id.action_unpin)?.isVisible = ids.isNotEmpty() && ids.all { it in pinned }
		return super.onPrepareActionMode(controller, mode, menu)
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_pin -> {
				viewModel.setPinned(selectedItemsIds, true)
				mode?.finish()
				true
			}

			R.id.action_unpin -> {
				viewModel.setPinned(selectedItemsIds, false)
				mode?.finish()
				true
			}

			R.id.action_remove -> {
				viewModel.removeFromFavourites(selectedItemsIds)
				mode?.finish()
				true
			}

			R.id.action_mark_current -> {
				val itemsSnapshot = selectedItems
				MaterialAlertDialogBuilder(context ?: return false)
					.setTitle(item.title)
					.setMessage(R.string.mark_as_completed_prompt)
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(android.R.string.ok) { _, _ ->
						viewModel.markAsRead(itemsSnapshot)
						mode?.finish()
					}.show()
				true
			}

			else -> super.onActionItemClicked(controller, mode, item)
		}
	}

	companion object {

		const val NO_ID = 0L
		private const val COVER_PREFETCH_BATCH = 24
		private const val MAX_REMEMBERED_COVERS = 256

		fun newInstance(categoryId: Long) = FavouritesListFragment().withArgs(1) {
			putLong(AppRouter.KEY_ID, categoryId)
		}
	}
}
