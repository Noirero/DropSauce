package org.koitharu.kotatsu.explore.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ActionMode
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.dialog.BigButtonsAlertDialog
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.ActionModeListener
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.ui.util.SpanSizeResolver
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.findAppCompatDelegate
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.recyclerView
import org.koitharu.kotatsu.core.util.ext.setTabsEnabled
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.FragmentExploreBinding
import org.koitharu.kotatsu.explore.data.ExploreContentClass
import org.koitharu.kotatsu.explore.ui.adapter.ExploreAdapter
import org.koitharu.kotatsu.explore.ui.adapter.ExploreListEventListener
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.adapter.bindBadge
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.parsers.model.Manga

@AndroidEntryPoint
class ExploreFragment :
	BaseFragment<FragmentExploreBinding>(),
	ActionModeListener,
	ExploreListEventListener,
	OnListItemClickListener<MangaSourceItem>, ListSelectionController.Callback {

	private val viewModel by viewModels<ExploreViewModel>()
	private var sourceSelectionController: ListSelectionController? = null
	private var manageBadge: BadgeDrawable? = null

	/** Page lists, indexed by page position. Both are created up-front by the pager. */
	private val pages = arrayOfNulls<RecyclerView>(2)
	private val pageHeights = IntArray(2)
	private val pageHeightDirty = BooleanArray(2) { true }
	private var measuredPagerWidth = 0
	private var pagerHeightUpdatePosted = false
	private var barsInsets: Insets = Insets.NONE

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentExploreBinding {
		return FragmentExploreBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: FragmentExploreBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		pageHeights.fill(0)
		pageHeightDirty.fill(true)
		measuredPagerWidth = 0
		pagerHeightUpdatePosted = false
		sourceSelectionController = ListSelectionController(
			appCompatDelegate = checkNotNull(findAppCompatDelegate()),
			decoration = SourceSelectionDecoration(binding.root.context),
			registryOwner = this,
			callback = this,
		)
		val header = binding.header
		val headerAdapter = ExploreAdapter(
			this,
			this,
			mangaClickListener = { manga, _ -> router.openDetails(manga) },
			onTipClose = { viewModel.dismissLanguageTip() },
		)
		with(header.recyclerViewHeader) {
			adapter = headerAdapter
			layoutManager = LinearLayoutManager(context)
			addItemDecoration(TypedListSpacingDecoration(context, false))
		}
		header.buttonManage.setOnClickListener { router.openSourcesCatalog(isExternalOnly = true) }

		binding.pager.adapter = ExploreSourcesPagerAdapter(::onPageCreated)
		binding.pager.offscreenPageLimit = 1
		// The pager's internal list opens a horizontal nested scroll on every touch-down. It does not
		// need to participate in the app bar's nested-scroll chain.
		binding.pager.recyclerView?.isNestedScrollingEnabled = false
		// A zero-height pager lays out no pages at all, so start at one screen and replace it with the
		// measured content height as soon as the extension pages are ready.
		binding.pager.updateLayoutParams { height = resources.displayMetrics.heightPixels }
		TabLayoutMediator(header.tabsKind, binding.pager) { tab, position ->
			tab.setText(if (position == 1) R.string.store_kind_novel else R.string.store_kind_manga)
		}.attach()
		actionModeDelegate.addListener(this)
		addMenuProvider(ExploreMenuProvider(router, viewModel))
		viewModel.headerContent.observe(viewLifecycleOwner, headerAdapter)
		viewModel.hasExtensionUpdates.observe(viewLifecycleOwner) { hasUpdates ->
			manageBadge = header.buttonManage.bindBadge(manageBadge, if (hasUpdates) "" else null)
		}
		viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.pager, this))
		viewModel.onOpenManga.observeEvent(viewLifecycleOwner, ::onOpenManga)
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.pager))
		viewModel.isGrid.observe(viewLifecycleOwner) { isGrid ->
			pages.forEach { it?.applyLayoutManager(isGrid) }
			invalidateAllPageHeights()
		}
		viewModel.onShowSuggestionsTip.observeEvent(viewLifecycleOwner) {
			showSuggestionsTip()
		}
	}

	private fun onPageCreated(recyclerView: RecyclerView, isNovel: Boolean) {
		val pageIndex = if (isNovel) 1 else 0
		val adapter = ExploreAdapter(
			this,
			this,
			mangaClickListener = { manga, _ -> router.openDetails(manga) },
			onTipClose = { viewModel.dismissLanguageTip() },
		)
		with(recyclerView) {
			this.adapter = adapter
			SpanSizeResolver(this, resources.getDimensionPixelSize(R.dimen.explore_grid_width)).attach()
			addItemDecoration(TypedListSpacingDecoration(context, false))
			checkNotNull(sourceSelectionController).attachToRecyclerView(this)
			applyLayoutManager(viewModel.isGrid.value)
			// The page can be laid out again when Favourites moves its header in/out of the activity app
			// bar. That changes height but not width, so measuring every page again here only stalls the
			// navigation frame. Content height is invalidated explicitly when data/layout mode changes;
			// layout only invalidates the cache when the available width actually changes.
			addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
				if (right - left != oldRight - oldLeft) {
					invalidateAllPageHeights()
				}
			}
		}
		pages[pageIndex] = recyclerView
		pageHeightDirty[pageIndex] = true
		viewModel.sources.observe(viewLifecycleOwner) { content ->
			adapter.emit(content[isNovel])
			recyclerView.resetPageScrollPosition()
			invalidatePageHeight(pageIndex)
			// Empty/loading states may finish sizing themselves one frame after the adapter commit. Do a
			// single follow-up invalidation instead of permanently remeasuring on every layout pass.
			recyclerView.postOnAnimation {
				recyclerView.resetPageScrollPosition()
				invalidatePageHeight(pageIndex)
			}
		}
	}

	private fun RecyclerView.applyLayoutManager(isGrid: Boolean) {
		val adapter = adapter as? ExploreAdapter ?: return
		layoutManager = if (isGrid) {
			object : GridLayoutManager(context, 4) {
				override fun canScrollVertically(): Boolean = false
			}.also { lm ->
				lm.spanSizeLookup = ExploreGridSpanSizeLookup(adapter, lm)
			}
		} else {
			object : LinearLayoutManager(context) {
				override fun canScrollVertically(): Boolean = false
			}
		}
		resetPageScrollPosition()
	}

	/**
	 * The source RecyclerViews are measurement-only children of the outer NestedScrollView. They must
	 * never retain their own vertical offset: while the pager starts at one-screen height, a normal
	 * LayoutManager can temporarily scroll the longer Manga page and keep that stale anchor after the
	 * pager grows, which produces the large blank gap seen above the extension grid.
	 */
	private fun RecyclerView.resetPageScrollPosition() {
		stopScroll()
		(layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		barsInsets = insets.systemBarsInsets
		val basePadding = v.resources.getDimensionPixelOffset(R.dimen.list_spacing_normal)
		viewBinding?.layoutContent?.setPadding(
			/* left = */ barsInsets.left + basePadding,
			/* top = */ basePadding,
			/* right = */ barsInsets.right + basePadding,
			/* bottom = */ barsInsets.bottom + basePadding,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onHiddenChanged(hidden: Boolean) {
		super.onHiddenChanged(hidden)
		if (!hidden && pageHeightDirty.any { it }) {
			schedulePagerHeightUpdate()
		}
	}

	private fun invalidatePageHeight(index: Int) {
		pageHeightDirty[index] = true
		if (!isHidden) {
			schedulePagerHeightUpdate()
		}
	}

	private fun invalidateAllPageHeights() {
		pageHeightDirty.fill(true)
		if (!isHidden) {
			schedulePagerHeightUpdate()
		}
	}

	private fun schedulePagerHeightUpdate() {
		val pager = viewBinding?.pager ?: return
		if (pagerHeightUpdatePosted || isHidden) return
		pagerHeightUpdatePosted = true
		// Keep full RecyclerView measurement out of the fragment/navigation commit itself. Multiple
		// invalidations in the same frame collapse into one measurement pass.
		pager.postOnAnimation {
			pagerHeightUpdatePosted = false
			updatePagerHeight()
		}
	}

	/**
	 * ViewPager2 cannot wrap its content, so the pager is given the height of the taller page. Both pages
	 * then keep that height, which is what makes switching tabs a no-op for the scroll position: the
	 * shorter list just ends in empty space. The expensive UNSPECIFIED RecyclerView measure is cached and
	 * repeated only when page content, grid/list mode, or available width changes.
	 */
	private fun updatePagerHeight() {
		val binding = viewBinding ?: return
		if (isHidden) return
		val width = binding.pager.width
		if (width == 0) {
			schedulePagerHeightUpdate()
			return
		}
		if (measuredPagerWidth != width) {
			measuredPagerWidth = width
			pageHeightDirty.fill(true)
		}
		val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
		val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
		for (index in pages.indices) {
			val page = pages[index] ?: continue
			if (!pageHeightDirty[index] && pageHeights[index] > 0) continue
			page.measure(widthSpec, heightSpec)
			pageHeights[index] = page.measuredHeight
			pageHeightDirty[index] = false
		}
		val height = pageHeights.maxOrNull() ?: 0
		if (height > 0 && binding.pager.layoutParams.height != height) {
			binding.pager.updateLayoutParams { this.height = height }
			// The first layout uses a temporary screen-sized pager. Once that constraint is removed, clear
			// any anchor that RecyclerView calculated while it was clipped so item 0 stays at the top.
			binding.pager.post {
				pages.forEach { it?.resetPageScrollPosition() }
			}
		}
	}

	override fun onDestroyView() {
		actionModeDelegate.removeListener(this)
		pages.fill(null)
		pageHeights.fill(0)
		pageHeightDirty.fill(true)
		measuredPagerWidth = 0
		pagerHeightUpdatePosted = false
		manageBadge = null
		sourceSelectionController = null
		super.onDestroyView()
	}

	override fun onActionModeStarted(mode: ActionMode) {
		viewBinding?.pager?.isUserInputEnabled = false
		viewBinding?.header?.tabsKind?.setTabsEnabled(false)
	}

	override fun onActionModeFinished(mode: ActionMode) {
		viewBinding?.pager?.isUserInputEnabled = true
		viewBinding?.header?.tabsKind?.setTabsEnabled(true)
	}

	override fun onListHeaderClick(item: ListHeader, view: View) {
		when (item.payload) {
			R.id.nav_suggestions -> router.openSuggestions()
			ExploreViewModel.HEADER_CONTENT_CLASSIFICATION -> Unit
			else -> router.openSourcesCatalog(isExternalOnly = true)
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_local -> router.openList(LocalMangaSource, null, null)
			R.id.button_bookmarks -> router.openBookmarks()
			R.id.button_downloads -> router.openDownloads()
		}
	}

	override fun onItemClick(item: MangaSourceItem, view: View) {
		if (sourceSelectionController?.onItemClick(item.id) == true) {
			return
		}
		router.openList(item.source, null, null)
	}

	override fun onItemLongClick(item: MangaSourceItem, view: View): Boolean {
		return sourceSelectionController?.onItemLongClick(view, item.id) == true
	}

	override fun onItemContextClick(item: MangaSourceItem, view: View): Boolean {
		return sourceSelectionController?.onItemContextClick(view, item.id) == true
	}

	override fun onRetryClick(error: Throwable) = Unit

	override fun onEmptyActionClick() {
		router.openSourcesCatalog(isExternalOnly = true)
	}

	override fun onSelectionChanged(controller: ListSelectionController, count: Int) {
		pages.forEach { it?.invalidateItemDecorations() }
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu,
	): Boolean {
		menuInflater.inflate(R.menu.mode_source, menu)
		return true
	}

	override fun onPrepareActionMode(controller: ListSelectionController, mode: ActionMode?, menu: Menu): Boolean {
		val selectedSources = viewModel.sourcesSnapshot(controller.peekCheckedIds())
		val isSingleSelection = selectedSources.size == 1
		menu.findItem(R.id.action_settings)?.isVisible = isSingleSelection
		menu.findItem(R.id.action_shortcut)?.isVisible = isSingleSelection
		menu.findItem(R.id.action_pin)?.isVisible = selectedSources.all { !it.isPinned }
		menu.findItem(R.id.action_unpin)?.isVisible = selectedSources.all { it.isPinned }
		menu.findItem(R.id.action_mark_sfw)?.isVisible = selectedSources.isNotEmpty()
		menu.findItem(R.id.action_mark_nsfw)?.isVisible = selectedSources.isNotEmpty()
		menu.findItem(R.id.action_reset_content_classification)?.isVisible =
			viewModel.hasManualContentClassification(selectedSources)
		menu.findItem(R.id.action_disable)?.isVisible = false
		menu.findItem(R.id.action_delete)?.isVisible = false
		return super.onPrepareActionMode(controller, mode, menu)
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
		val selectedSources = viewModel.sourcesSnapshot(controller.peekCheckedIds())
		if (selectedSources.isEmpty()) {
			return false
		}
		when (item.itemId) {
			R.id.action_settings -> {
				val source = selectedSources.singleOrNull() ?: return false
				router.openSourceSettings(source)
				mode?.finish()
			}

			R.id.action_shortcut -> {
				val source = selectedSources.singleOrNull() ?: return false
				viewModel.requestPinShortcut(source)
				mode?.finish()
			}

			R.id.action_pin -> {
				viewModel.setSourcesPinned(selectedSources, isPinned = true)
				mode?.finish()
			}

			R.id.action_unpin -> {
				viewModel.setSourcesPinned(selectedSources, isPinned = false)
				mode?.finish()
			}

			R.id.action_mark_sfw -> {
				viewModel.setContentClassification(selectedSources, ExploreContentClass.SFW)
				mode?.finish()
			}

			R.id.action_mark_nsfw -> {
				viewModel.setContentClassification(selectedSources, ExploreContentClass.NSFW)
				mode?.finish()
			}

			R.id.action_reset_content_classification -> {
				viewModel.setContentClassification(selectedSources, null)
				mode?.finish()
			}

			R.id.action_hide -> {
				viewModel.hideSources(selectedSources)
				mode?.finish()
			}

			else -> return false
		}
		return true
	}

	private fun onOpenManga(manga: Manga) {
		router.openDetails(manga)
	}

	private fun showSuggestionsTip() {
		val listener = DialogInterface.OnClickListener { _, which ->
			viewModel.respondSuggestionTip(which == DialogInterface.BUTTON_POSITIVE)
		}
		BigButtonsAlertDialog.Builder(requireContext())
			.setIcon(R.drawable.ic_suggestion)
			.setTitle(R.string.suggestions_enable_prompt)
			.setPositiveButton(R.string.enable, listener)
			.setNegativeButton(R.string.no_thanks, listener)
			.create()
			.show()
	}
}
