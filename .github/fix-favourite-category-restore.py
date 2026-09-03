from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

MANUAL_HELPER = '''name: Beta Build Once

# Temporary helper kept manual-only after the requested Beta build.
on:
  workflow_dispatch:

permissions:
  contents: read

jobs:
  beta:
    name: Build beta APK
    runs-on: ubuntu-latest
    steps:
      - name: Manual helper disabled
        run: echo "Use preview-build.yml for normal manual Beta builds."
'''


def replace_exact(path: Path, old: str, new: str, label: str, expected: int = 1) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f'{label}: expected {expected}, found {count}')
    path.write_text(text.replace(old, new))


# Adapter: notify the Fragment only after AsyncListDiffer has committed the new category list.
adapter = ROOT / 'app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/container/FavouritesContainerAdapter.kt'
replace_exact(
    adapter,
    '''class FavouritesContainerAdapter(\n\tprivate val fragment: Fragment,\n\tprivate val showCategoryCounts: () -> Boolean,\n) : FragmentStateAdapter(fragment), FlowCollector<List<FavouriteTabModel>> {''',
    '''class FavouritesContainerAdapter(\n\tprivate val fragment: Fragment,\n\tprivate val showCategoryCounts: () -> Boolean,\n\tprivate val onListCommitted: (List<FavouriteTabModel>) -> Unit = {},\n) : FragmentStateAdapter(fragment), FlowCollector<List<FavouriteTabModel>> {''',
    'adapter commit callback constructor',
)
replace_exact(
    adapter,
    '''\t\t\tupdateTabBadgeNumbers(value)\n\t\t\tContinuationResumeRunnable(cont).run()''',
    '''\t\t\tupdateTabBadgeNumbers(value)\n\t\t\tonListCommitted(differ.currentList)\n\t\t\tContinuationResumeRunnable(cont).run()''',
    'adapter commit callback invocation',
)

fragment = ROOT / 'app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/container/FavouritesContainerFragment.kt'
replace_exact(
    fragment,
    '''\t\tval adapter = FavouritesContainerAdapter(this) {\n\t\t\tdisplayPreferences.current(contentTypeStore.selectedType.value).showCategoryCounts\n\t\t}''',
    '''\t\tval adapter = FavouritesContainerAdapter(\n\t\t\tfragment = this,\n\t\t\tshowCategoryCounts = {\n\t\t\t\tdisplayPreferences.current(contentTypeStore.selectedType.value).showCategoryCounts\n\t\t\t},\n\t\t\tonListCommitted = ::onCategoriesCommitted,\n\t\t)''',
    'adapter construction',
)
replace_exact(
    fragment,
    '''\t\tbinding.pager.offscreenPageLimit = 1\n\t\tbinding.pager.recyclerView?.isNestedScrollingEnabled = false''',
    '''\t\tbinding.pager.offscreenPageLimit = 1\n\t\tbinding.pager.recyclerView?.apply {\n\t\t\tisNestedScrollingEnabled = false\n\t\t\titemAnimator = null\n\t\t}''',
    'pager animation reduction',
)
replace_exact(
    fragment,
    '''\t\t\tif (contentTypeStore.selectedType.value != type) {\n\t\t\t\tcontentTypeStore.setSelectedType(type)\n\t\t\t}''',
    '''\t\t\tif (contentTypeStore.selectedType.value != type) {\n\t\t\t\trememberCurrentCategory()\n\t\t\t\tcontentTypeStore.setSelectedType(type)\n\t\t\t}''',
    'save category before content type switch',
)
replace_exact(
    fragment,
    '''\t\tviewModel.categories.observe(viewLifecycleOwner, adapter)\n\t\tviewModel.categories.observe(viewLifecycleOwner, ::onCategoriesChanged)''',
    '''\t\tviewModel.categories.observe(viewLifecycleOwner, adapter)''',
    'remove pre-commit category observer',
)
replace_exact(
    fragment,
    '''\tprivate val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {\n\t\toverride fun onPageSelected(position: Int) {\n\t\t\tif (pendingCategoryRestore == null) {\n\t\t\t\tcurrentCategory()?.let { category ->\n\t\t\t\t\tcontentTypeStore.setLastCategoryId(\n\t\t\t\t\t\tdisplayedContentType ?: contentTypeStore.selectedType.value,\n\t\t\t\t\t\tcategory.id,\n\t\t\t\t\t)\n\t\t\t\t}\n\t\t\t}\n\t\t\tupdateCategoryPickerLabel()\n\t\t\tactivity?.invalidateOptionsMenu()\n\t\t}\n\t}''',
    '''\tprivate val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {\n\t\toverride fun onPageSelected(position: Int) {\n\t\t\tif (pendingCategoryRestore == null) {\n\t\t\t\trememberCurrentCategory()\n\t\t\t}\n\t\t\tupdateCategoryPickerLabel()\n\t\t\tactivity?.invalidateOptionsMenu()\n\t\t}\n\t}''',
    'centralize category persistence',
)
replace_exact(
    fragment,
    '''\toverride fun onDestroyView() {\n\t\tviewBinding?.pager?.unregisterOnPageChangeCallback(pageChangeCallback)''',
    '''\toverride fun onDestroyView() {\n\t\trememberCurrentCategory()\n\t\tviewBinding?.pager?.unregisterOnPageChangeCallback(pageChangeCallback)''',
    'persist category on view destroy',
)

old_categories = '''\tprivate fun onCategoriesChanged(value: List<FavouriteTabModel>) {\n\t\tcategories = value\n\t\tactivity?.invalidateOptionsMenu()\n\t\tviewBinding?.run {\n\t\t\ttabs.post {\n\t\t\t\tval binding = viewBinding ?: return@post\n\t\t\t\tval restoreType = pendingCategoryRestore\n\t\t\t\tif (restoreType != null && isCategoryListForType(value, restoreType)) {\n\t\t\t\t\tval categoryId = contentTypeStore.getLastCategoryId(restoreType)\n\t\t\t\t\tval target = value.indexOfFirst { it.id == categoryId }.takeIf { it >= 0 } ?: 0\n\t\t\t\t\tpendingCategoryRestore = null\n\t\t\t\t\tif (value.isNotEmpty()) {\n\t\t\t\t\t\tbinding.pager.setCurrentItem(target, false)\n\t\t\t\t\t\tcontentTypeStore.setLastCategoryId(restoreType, value[target].id)\n\t\t\t\t\t}\n\t\t\t\t} else if (restoreType == null && value.isNotEmpty() && binding.pager.currentItem >= value.size) {\n\t\t\t\t\tbinding.pager.setCurrentItem(0, false)\n\t\t\t\t}\n\t\t\t\tapplyCategoryNavigation(displayPreferences.current(contentTypeStore.selectedType.value))\n\t\t\t}\n\t\t}\n\t}\n'''
new_categories = '''\tprivate fun onCategoriesCommitted(value: List<FavouriteTabModel>) {\n\t\tcategories = value\n\t\tactivity?.invalidateOptionsMenu()\n\t\tval binding = viewBinding ?: return\n\t\tval restoreType = pendingCategoryRestore\n\t\tif (restoreType != null && isCategoryListForType(value, restoreType)) {\n\t\t\tval categoryId = contentTypeStore.getLastCategoryId(restoreType)\n\t\t\tval target = value.indexOfFirst { it.id == categoryId }.takeIf { it >= 0 } ?: 0\n\t\t\tif (value.isNotEmpty()) {\n\t\t\t\tbinding.pager.setCurrentItem(target, false)\n\t\t\t\tcontentTypeStore.setLastCategoryId(restoreType, value[target].id)\n\t\t\t}\n\t\t\tpendingCategoryRestore = null\n\t\t} else if (restoreType == null && value.isNotEmpty() && binding.pager.currentItem >= value.size) {\n\t\t\tbinding.pager.setCurrentItem(0, false)\n\t\t}\n\t\tapplyCategoryNavigation(displayPreferences.current(contentTypeStore.selectedType.value))\n\t}\n'''
replace_exact(fragment, old_categories, new_categories, 'restore only after adapter commit')
replace_exact(
    fragment,
    '''\tprivate fun currentCategory(): FavouriteTabModel? {\n\t\tval position = viewBinding?.pager?.currentItem ?: return null\n\t\treturn categories.getOrNull(position)\n\t}\n\n\tprivate fun currentFavouritesList(): FavouritesListFragment? =''',
    '''\tprivate fun currentCategory(): FavouriteTabModel? {\n\t\tval position = viewBinding?.pager?.currentItem ?: return null\n\t\treturn categories.getOrNull(position)\n\t}\n\n\tprivate fun rememberCurrentCategory() {\n\t\tval type = displayedContentType ?: return\n\t\tval category = currentCategory() ?: return\n\t\tcontentTypeStore.setLastCategoryId(type, category.id)\n\t}\n\n\tprivate fun currentFavouritesList(): FavouritesListFragment? =''',
    'remember current category helper',
)

layout = ROOT / 'app/src/main/res/layout/fragment_favourites_container.xml'
replace_exact(
    layout,
    '''\t\t\t\tandroid:background="@null"\n\t\t\t\tapp:tabGravity="start"''',
    '''\t\t\t\tandroid:background="@null"\n\t\t\t\tapp:tabIndicatorAnimationDuration="0"\n\t\t\t\tapp:tabGravity="start"''',
    'disable favourite tab indicator animation',
)

# Restore the temporary helper and remove this patch script in the source commit.
helper = ROOT / '.github/workflows/beta-build-once.yml'
helper.write_text(MANUAL_HELPER)
Path(__file__).unlink()
