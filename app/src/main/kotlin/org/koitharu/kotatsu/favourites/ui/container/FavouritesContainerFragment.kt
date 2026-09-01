package org.koitharu.kotatsu.favourites.ui.container

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.search.SearchBar
import com.google.android.material.search.SearchView
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.util.ActionModeListener
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.centerContentOnDisplay
import org.koitharu.kotatsu.core.util.ext.findCurrentPagerFragment
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.recyclerView
import org.koitharu.kotatsu.core.util.ext.setTabsEnabled
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.databinding.FragmentFavouritesContainerBinding
import org.koitharu.kotatsu.databinding.ItemEmptyStateBinding
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner

@AndroidEntryPoint
class FavouritesContainerFragment : BaseFragment<FragmentFavouritesContainerBinding>(),
	ActionModeListener,
	RecyclerViewOwner,
	ViewStub.OnInflateListener,
	View.OnClickListener {

	private val viewModel: FavouritesContainerViewModel by viewModels()
	private var inlineSearchEdit: AppCompatEditText? = null
	private var inlineSearchActive = false
	private var searchBackCallback: OnBackPressedCallback? = null

	override val recyclerView: RecyclerView?
		get() = (findCurrentFragment() as? RecyclerViewOwner)?.recyclerView

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentFavouritesContainerBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentFavouritesContainerBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		searchScopeActive.value = !isHidden
		val pagerAdapter = FavouritesContainerAdapter(this)
		binding.pager.adapter = pagerAdapter
		binding.pager.offscreenPageLimit = 1
		binding.pager.recyclerView?.isNestedScrollingEnabled = false
		TabLayoutMediator(
			binding.tabs,
			binding.pager,
			FavouritesTabConfigurationStrategy(pagerAdapter, viewModel, router),
		).attach()
		binding.stubEmpty.setOnInflateListener(this)
		if (!isHidden) {
			attachTabsToAppBar()
			installFavouriteSearchHandler()
		}
		actionModeDelegate.addListener(this)
		viewModel.categories.observe(viewLifecycleOwner, pagerAdapter)
		viewModel.isEmpty.observe(viewLifecycleOwner, ::onEmptyStateChanged)
		addMenuProvider(FavouritesContainerMenuProvider(router))
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.pager))

		searchBackCallback = object : OnBackPressedCallback(false) {
			override fun handleOnBackPressed() = exitInlineSearch()
		}.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }
	}

	override fun onDestroyView() {
		exitInlineSearch()
		restoreGlobalSearchHandler()
		inlineSearchEdit?.let { edit -> (edit.parent as? ViewGroup)?.removeView(edit) }
		inlineSearchEdit = null
		searchBackCallback = null
		searchScopeActive.value = false
		searchQuery.value = ""
		detachTabsFromAppBar()
		actionModeDelegate.removeListener(this)
		super.onDestroyView()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	override fun onHiddenChanged(hidden: Boolean) {
		super.onHiddenChanged(hidden)
		searchScopeActive.value = !hidden
		if (hidden) {
			exitInlineSearch()
			restoreGlobalSearchHandler()
		} else {
			installFavouriteSearchHandler()
			// This tab is kept alive across bottom-nav switches, so its category lists would retain
			// their previous scroll. Reset every instantiated category page (the visible one plus any
			// cached offscreen pages) to the top whenever Favourites is reopened, matching the other tabs.
			for (page in childFragmentManager.fragments) {
				val recyclerView = (page as? RecyclerViewOwner)?.recyclerView ?: continue
				when (val lm = recyclerView.layoutManager) {
					is LinearLayoutManager -> lm.scrollToPositionWithOffset(0, 0)
					else -> recyclerView.scrollToPosition(0)
				}
			}
		}
	}

	override fun onActionModeStarted(mode: ActionMode) {
		viewBinding?.run {
			pager.isUserInputEnabled = false
			tabs.setTabsEnabled(false)
		}
	}

	override fun onActionModeFinished(mode: ActionMode) {
		viewBinding?.run {
			pager.isUserInputEnabled = true
			tabs.setTabsEnabled(true)
		}
	}

	override fun onInflate(stub: ViewStub?, inflated: View) {
		val stubBinding = ItemEmptyStateBinding.bind(inflated)
		inflated.centerContentOnDisplay()
		stubBinding.icon.setImageAsync(R.drawable.ic_empty_favourites)
		stubBinding.textPrimary.setText(R.string.text_empty_holder_primary)
		stubBinding.textSecondary.setTextAndVisible(R.string.empty_favourite_categories)
		stubBinding.buttonRetry.setTextAndVisible(R.string.manage)
		stubBinding.buttonRetry.setOnClickListener(this)
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_retry -> router.openFavoriteCategories()
		}
	}

	private fun onEmptyStateChanged(isEmpty: Boolean) {
		viewBinding?.run {
			pager.isGone = isEmpty
			tabs.isGone = isEmpty
			stubEmpty.isVisible = isEmpty
		}
	}

	private fun findCurrentFragment(): Fragment? {
		return childFragmentManager.findCurrentPagerFragment(
			viewBinding?.pager ?: return null,
		)
	}

	private fun installFavouriteSearchHandler() {
		val searchBar = activity?.findViewById<SearchBar>(R.id.search_bar) ?: return
		searchBar.setOnClickListener { enterInlineSearch() }
	}

	private fun restoreGlobalSearchHandler() {
		val host = activity ?: return
		val searchBar = host.findViewById<SearchBar>(R.id.search_bar) ?: return
		val searchView = host.findViewById<SearchView>(R.id.search_view) ?: return
		searchBar.setOnClickListener { searchView.show() }
	}

	private fun enterInlineSearch() {
		if (inlineSearchActive) return
		val host = activity ?: return
		val searchBar = host.findViewById<SearchBar>(R.id.search_bar) ?: return
		val edit = inlineSearchEdit ?: createInlineSearchEdit(searchBar) ?: return
		inlineSearchActive = true
		searchBar.isGone = true
		edit.isVisible = true
		searchBackCallback?.isEnabled = true
		host.findViewById<MaterialButton>(R.id.button_settings)?.apply {
			setIconResource(R.drawable.ic_arrow_back)
			contentDescription = getString(R.string.close)
			setOnClickListener { exitInlineSearch() }
		}
		edit.requestFocus()
		edit.post {
			(context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
				?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
		}
	}

	private fun exitInlineSearch() {
		val host = activity ?: return
		val searchBar = host.findViewById<SearchBar>(R.id.search_bar) ?: return
		val edit = inlineSearchEdit
		inlineSearchActive = false
		searchBackCallback?.isEnabled = false
		searchQuery.value = ""
		edit?.apply {
			setText("")
			clearFocus()
			isGone = true
		}
		searchBar.isVisible = true
		host.findViewById<MaterialButton>(R.id.button_settings)?.apply {
			setIconResource(R.drawable.ic_settings)
			contentDescription = getString(R.string.settings)
			setOnClickListener { router.openSettings() }
		}
		if (edit != null) {
			(host.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
				?.hideSoftInputFromWindow(edit.windowToken, 0)
		}
	}

	private fun createInlineSearchEdit(searchBar: SearchBar): AppCompatEditText? {
		val parent = searchBar.parent as? LinearLayout ?: return null
		val density = resources.displayMetrics.density
		val edit = AppCompatEditText(requireContext()).apply {
			layoutParams = LinearLayout.LayoutParams(searchBar.layoutParams)
			background = searchBar.background?.constantState?.newDrawable(resources)?.mutate()
			hint = getString(R.string.search_manga)
			setTextColor(searchBar.textView.currentTextColor)
			setHintTextColor(searchBar.textView.currentHintTextColor)
			textSize = searchBar.textView.textSize / resources.displayMetrics.scaledDensity
			gravity = Gravity.CENTER_VERTICAL
			isSingleLine = true
			maxLines = 1
			minimumHeight = (56f * density).toInt()
			setPadding((20f * density).toInt(), 0, (20f * density).toInt(), 0)
			setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
			compoundDrawablePadding = resources.getDimensionPixelOffset(R.dimen.margin_small)
			isGone = true
			doAfterTextChanged { searchQuery.value = it?.toString().orEmpty() }
		}
		parent.addView(edit, parent.indexOfChild(searchBar) + 1)
		inlineSearchEdit = edit
		return edit
	}

	// The category tabs live in the activity's AppBarLayout while this tab is visible, so they scroll
	// off-screen together with the search bar instead of being pinned above the (edge-to-edge) lists.
	// Called by MainActivity at bottom-nav commit time, not from onHiddenChanged: that callback is only
	// delivered after the tab-switch animation finishes, so the app bar would grow by the tab strip's
	// height a frame after the crossfade ended and visibly shove the list down.
	fun attachTabsToAppBar() {
		val tabs = viewBinding?.tabs ?: return
		val appBar = (activity as? AppBarOwner)?.appBar ?: return
		if (tabs.parent === appBar) {
			return
		}
		(tabs.parent as? ViewGroup)?.removeView(tabs)
		appBar.addView(
			tabs,
			AppBarLayout.LayoutParams(
				AppBarLayout.LayoutParams.MATCH_PARENT,
				AppBarLayout.LayoutParams.WRAP_CONTENT,
			).apply {
				scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
					AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS or
					AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
			},
		)
	}

	fun detachTabsFromAppBar() {
		val tabs = viewBinding?.tabs ?: return
		val parent = tabs.parent as? ViewGroup ?: return
		if (parent !== viewBinding?.root) {
			parent.removeView(tabs)
		}
	}

	companion object {
		internal val searchScopeActive = MutableStateFlow(false)
		internal val searchQuery = MutableStateFlow("")
	}
}
