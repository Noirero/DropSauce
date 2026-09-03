package org.koitharu.kotatsu.favourites.ui.container

import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.FlowCollector
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ContinuationResumeRunnable
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment
import org.koitharu.kotatsu.favourites.ui.list.LocalFavouritesListFragment
import kotlin.coroutines.suspendCoroutine

class FavouritesContainerAdapter(
	private val fragment: Fragment,
) : FragmentStateAdapter(fragment), FlowCollector<List<FavouriteTabModel>> {

	private val differ = AsyncListDiffer(
		AdapterListUpdateCallback(this),
		AsyncDifferConfig.Builder(FavouriteTabDiffCallback)
			.setBackgroundThreadExecutor(Dispatchers.Default.limitedParallelism(2).asExecutor())
			.build(),
	)

	override fun getItemCount(): Int = differ.currentList.size

	override fun getItemId(position: Int): Long {
		return differ.currentList.getOrNull(position)?.id ?: RecyclerView.NO_ID
	}

	override fun containsItem(itemId: Long): Boolean {
		return differ.currentList.any { x -> x.id == itemId }
	}

	override fun createFragment(position: Int): Fragment {
		val item = differ.currentList[position]
		return if (item.id == LOCAL_FAVOURITES_CATEGORY_ID) {
			LocalFavouritesListFragment()
		} else {
			FavouritesListFragment.newInstance(item.id)
		}
	}

	override suspend fun emit(value: List<FavouriteTabModel>) = suspendCoroutine { cont ->
		differ.submitList(value) {
			// Count-only changes are deliberately excluded from the ViewPager diff below. Rebuilding
			// tabs for every count update makes TabLayoutMediator recreate every Material badge and can
			// monopolize the main thread on large/active libraries. Update the already attached badges
			// directly instead, including visibility because initial tabs are normally created before the
			// asynchronous count snapshot arrives.
			updateTabBadgeNumbers(value)
			ContinuationResumeRunnable(cont).run()
		}
	}

	fun getItem(position: Int): FavouriteTabModel = differ.currentList[position]

	private fun updateTabBadgeNumbers(items: List<FavouriteTabModel>) {
		val tabs = fragment.view?.findViewById<TabLayout>(R.id.tabs) ?: return
		if (tabs.tabCount != items.size) return
		for (index in items.indices) {
			val count = items[index].count.coerceAtMost(MAX_CATEGORY_BADGE_COUNT)
			val badge = tabs.getTabAt(index)?.getOrCreateBadge() ?: continue
			badge.maxCharacterCount = 6
			if (badge.number != count) {
				badge.number = count
			}
			// A tab commonly starts at count=0, so its badge is hidden by the configuration strategy.
			// When the deferred count arrives we must explicitly reveal it; changing number alone does
			// not change Material BadgeDrawable visibility.
			badge.isVisible = count > 0
		}
	}

	private object FavouriteTabDiffCallback : DiffUtil.ItemCallback<FavouriteTabModel>() {

		override fun areItemsTheSame(oldItem: FavouriteTabModel, newItem: FavouriteTabModel): Boolean {
			return oldItem.id == newItem.id
		}

		override fun areContentsTheSame(oldItem: FavouriteTabModel, newItem: FavouriteTabModel): Boolean {
			// Count changes do not alter ViewPager structure/content. They are applied directly to badges
			// after the differ commits the new list, avoiding TabLayoutMediator's full tab repopulation.
			return oldItem.id == newItem.id && oldItem.title == newItem.title
		}
	}

	private companion object {
		const val MAX_CATEGORY_BADGE_COUNT = 99_999
	}
}
