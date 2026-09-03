from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:120]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"Expected exactly one match in {path}, got {text.count(old)}")
    p.write_text(text.replace(old, new, 1))

# 1) Make the virtual Downloaded list query local_index directly, while keeping the
# normal Downloaded quick-filter on favourites unchanged.
dao = "app/src/main/kotlin/org/koitharu/kotatsu/favourites/data/FavouritesDao.kt"
replace_once(
    dao,
    "import org.koitharu.kotatsu.core.db.entity.MangaWithTags\n",
    "import org.koitharu.kotatsu.core.db.entity.MangaEntity\nimport org.koitharu.kotatsu.core.db.entity.MangaWithTags\n",
)
replace_once(
    dao,
    "import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_COMPLETED\n",
    "import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_COMPLETED\nimport org.koitharu.kotatsu.local.data.index.LocalMangaIndexEntity\n",
)
replace_once(
    dao,
    '''\t@Query(\n\t\t"SELECT DISTINCT manga.manga_id AS manga_id, manga.title AS title, manga.author AS author, manga.source AS source " +\n\t\t\t"FROM favourites INNER JOIN manga ON manga.manga_id = favourites.manga_id " +\n\t\t\t"INNER JOIN local_index ON local_index.manga_id = favourites.manga_id " +\n\t\t\t"WHERE favourites.deleted_at = 0 AND " +\n\t\t\t"(SELECT show_in_lib FROM favourite_categories WHERE favourite_categories.category_id = favourites.category_id) = 1",\n\t)\n\tabstract suspend fun findDownloadedSearchEntries(): List<FavouriteSearchEntry>\n\n\t@Query(\n\t\t"SELECT manga.source AS source, COUNT(DISTINCT favourites.manga_id) AS item_count " +\n\t\t\t"FROM favourites INNER JOIN manga ON manga.manga_id = favourites.manga_id " +\n\t\t\t"INNER JOIN local_index ON local_index.manga_id = favourites.manga_id " +\n\t\t\t"WHERE favourites.deleted_at = 0 AND " +\n\t\t\t"(SELECT show_in_lib FROM favourite_categories WHERE favourite_categories.category_id = favourites.category_id) = 1 " +\n\t\t\t"GROUP BY manga.source",\n\t)\n\tabstract suspend fun findDownloadedCountsBySource(): List<FavouriteSourceCount>\n''',
    '''\t@Query(\n\t\t"SELECT manga.manga_id AS manga_id, manga.title AS title, manga.author AS author, manga.source AS source " +\n\t\t\t"FROM local_index INNER JOIN manga ON manga.manga_id = local_index.manga_id",\n\t)\n\tabstract suspend fun findDownloadedSearchEntries(): List<FavouriteSearchEntry>\n\n\t@Query(\n\t\t"SELECT manga.source AS source, COUNT(DISTINCT local_index.manga_id) AS item_count " +\n\t\t\t"FROM local_index INNER JOIN manga ON manga.manga_id = local_index.manga_id " +\n\t\t\t"GROUP BY manga.source",\n\t)\n\tabstract suspend fun findDownloadedCountsBySource(): List<FavouriteSourceCount>\n''',
)
replace_once(
    dao,
    '''\tfun observeAll(\n\t\torder: ListSortOrder,\n\t\tfilterOptions: Set<ListFilterOption>,\n\t\tlimit: Int,\n\t\tpinned: List<Long> = emptyList(),\n\t): Flow<List<FavouriteManga>> = observeAll(0L, order, filterOptions, limit, pinned)\n''',
    '''\tfun observeAll(\n\t\torder: ListSortOrder,\n\t\tfilterOptions: Set<ListFilterOption>,\n\t\tlimit: Int,\n\t\tpinned: List<Long> = emptyList(),\n\t): Flow<List<FavouriteManga>> = observeAll(0L, order, filterOptions, limit, pinned)\n\n\t/**\n\t * Virtual Downloaded shelf. Unlike the normal favourites query, local_index is the root table so\n\t * an on-device title does not have to be favourited to appear here.\n\t */\n\tfun observeDownloaded(\n\t\torder: ListSortOrder,\n\t\tfilterOptions: Set<ListFilterOption>,\n\t\tlimit: Int,\n\t\tpinned: List<Long> = emptyList(),\n\t): Flow<List<MangaWithTags>> = observeDownloadedImpl(\n\t\tMangaQueryBuilder("manga", ::getDownloadedCondition)\n\t\t\t.join("INNER JOIN local_index ON local_index.manga_id = manga.manga_id")\n\t\t\t.filters(filterOptions - ListFilterOption.Downloaded)\n\t\t\t.orderBy(getDownloadedOrderBy(order, pinned))\n\t\t\t.limit(limit)\n\t\t\t.build(),\n\t)\n''',
)
replace_once(
    dao,
    '''\t@Transaction\n\t@RawQuery(observedEntities = [FavouriteEntity::class])\n\tprotected abstract fun observeAllImpl(query: SupportSQLiteQuery): Flow<List<FavouriteManga>>\n''',
    '''\t@Transaction\n\t@RawQuery(observedEntities = [FavouriteEntity::class])\n\tprotected abstract fun observeAllImpl(query: SupportSQLiteQuery): Flow<List<FavouriteManga>>\n\n\t@Transaction\n\t@RawQuery(observedEntities = [LocalMangaIndexEntity::class, MangaEntity::class])\n\tprotected abstract fun observeDownloadedImpl(query: SupportSQLiteQuery): Flow<List<MangaWithTags>>\n''',
)
replace_once(
    dao,
    '''\tprivate fun getOrderBy(sortOrder: ListSortOrder) = sortOrder.toOrderBy(\n\t\tdateAdded = "favourites.created_at",\n\t\tlastRead = "IFNULL((SELECT updated_at FROM history WHERE history.manga_id = manga.manga_id), 0)",\n\t\tprogress = "IFNULL((SELECT percent FROM history WHERE history.manga_id = manga.manga_id), 0)",\n\t)\n\n\toverride fun getCondition(option: ListFilterOption): String? = when (option) {\n''',
    '''\tprivate fun getOrderBy(sortOrder: ListSortOrder) = sortOrder.toOrderBy(\n\t\tdateAdded = "favourites.created_at",\n\t\tlastRead = "IFNULL((SELECT updated_at FROM history WHERE history.manga_id = manga.manga_id), 0)",\n\t\tprogress = "IFNULL((SELECT percent FROM history WHERE history.manga_id = manga.manga_id), 0)",\n\t)\n\n\tprivate fun getDownloadedOrderBy(sortOrder: ListSortOrder, pinned: List<Long>): String {\n\t\tval orderBy = sortOrder.toOrderBy(\n\t\t\tdateAdded = "manga.details_updated_at",\n\t\t\tlastRead = "IFNULL((SELECT updated_at FROM history WHERE history.manga_id = manga.manga_id), 0)",\n\t\t\tprogress = "IFNULL((SELECT percent FROM history WHERE history.manga_id = manga.manga_id), 0)",\n\t\t)\n\t\tif (pinned.isEmpty()) return orderBy\n\t\tval case = buildString {\n\t\t\tappend("CASE manga.manga_id")\n\t\t\tpinned.forEachIndexed { index, id -> append(" WHEN $id THEN $index") }\n\t\t\tappend(" ELSE ${pinned.size} END")\n\t\t}\n\t\treturn "$case, $orderBy"\n\t}\n\n\tprivate fun getDownloadedCondition(option: ListFilterOption): String? = when (option) {\n\t\tListFilterOption.Macro.COMPLETED -> "EXISTS(SELECT * FROM history WHERE history.manga_id = manga.manga_id AND history.percent >= $PROGRESS_COMPLETED)"\n\t\tListFilterOption.Macro.NEW_CHAPTERS -> "(SELECT chapters_new FROM tracks WHERE tracks.manga_id = manga.manga_id) > 0"\n\t\tListFilterOption.Macro.FAVORITE -> "EXISTS(SELECT * FROM favourites WHERE favourites.manga_id = manga.manga_id AND favourites.deleted_at = 0)"\n\t\tListFilterOption.Macro.NSFW -> "manga.nsfw = 1"\n\t\tis ListFilterOption.Tag -> "EXISTS(SELECT * FROM manga_tags WHERE manga.manga_id = manga_tags.manga_id AND tag_id = ${option.tagId})"\n\t\tListFilterOption.Downloaded -> "1"\n\t\tis ListFilterOption.Source -> "manga.source = ${sqlEscapeString(option.mangaSource.name)}"\n\t\tis ListFilterOption.State -> option.state?.let { "manga.state = ${sqlEscapeString(it.name)}" }\n\t\telse -> null\n\t}\n\n\toverride fun getCondition(option: ListFilterOption): String? = when (option) {\n''',
)

# 2) Add a direct local-index observer while preserving the old favourite+Downloaded quick-filter.
observer = "app/src/main/kotlin/org/koitharu/kotatsu/favourites/domain/LocalFavoritesObserver.kt"
replace_once(
    observer,
    "import kotlinx.coroutines.flow.Flow\n",
    "import kotlinx.coroutines.flow.Flow\nimport kotlinx.coroutines.flow.map\nimport kotlinx.coroutines.flow.onStart\n",
)
replace_once(
    observer,
    "import org.koitharu.kotatsu.core.db.entity.toMangaTags\n",
    "import org.koitharu.kotatsu.core.db.entity.toMangaList\nimport org.koitharu.kotatsu.core.db.entity.toMangaTags\n",
)
replace_once(
    observer,
    '''class LocalFavoritesObserver @Inject constructor(\n\tlocalMangaIndex: LocalMangaIndex,\n\tprivate val db: MangaDatabase,\n) : LocalObserveMapper<FavouriteManga, Manga>(localMangaIndex) {\n''',
    '''class LocalFavoritesObserver @Inject constructor(\n\tprivate val localMangaIndex: LocalMangaIndex,\n\tprivate val db: MangaDatabase,\n) : LocalObserveMapper<FavouriteManga, Manga>(localMangaIndex) {\n''',
)
replace_once(
    observer,
    '''\tfun observeAll(\n\t\tcategoryId: Long,\n\t\torder: ListSortOrder,\n\t\tfilterOptions: Set<ListFilterOption>,\n\t\tlimit: Int\n\t): Flow<List<Manga>> = db.getFavouritesDao().observeAll(categoryId, order, filterOptions, limit).mapToLocal()\n\n\toverride fun toManga(e: FavouriteManga) = e.manga.toManga(e.tags.toMangaTags(), null)\n''',
    '''\tfun observeAll(\n\t\tcategoryId: Long,\n\t\torder: ListSortOrder,\n\t\tfilterOptions: Set<ListFilterOption>,\n\t\tlimit: Int\n\t): Flow<List<Manga>> = db.getFavouritesDao().observeAll(categoryId, order, filterOptions, limit).mapToLocal()\n\n\tfun observeDownloaded(\n\t\torder: ListSortOrder,\n\t\tfilterOptions: Set<ListFilterOption>,\n\t\tlimit: Int,\n\t\tpinned: List<Long>,\n\t): Flow<List<Manga>> = db.getFavouritesDao()\n\t\t.observeDownloaded(order, filterOptions, limit, pinned)\n\t\t.onStart { localMangaIndex.updateIfRequired() }\n\t\t.map { it.toMangaList() }\n\n\toverride fun toManga(e: FavouriteManga) = e.manga.toManga(e.tags.toMangaTags(), null)\n''',
)

# 3) Expose the direct observer from the repository.
repo = "app/src/main/kotlin/org/koitharu/kotatsu/favourites/domain/FavouritesRepository.kt"
replace_once(
    repo,
    '''\tfun observeAll(\n\t\torder: ListSortOrder,\n\t\tfilterOptions: Set<ListFilterOption>,\n\t\tlimit: Int,\n\t\tpinned: List<Long> = emptyList(),\n\t): Flow<List<Manga>> {\n''',
    '''\tfun observeDownloaded(\n\t\torder: ListSortOrder,\n\t\tfilterOptions: Set<ListFilterOption>,\n\t\tlimit: Int,\n\t\tpinned: List<Long> = emptyList(),\n\t): Flow<List<Manga>> = localObserver.observeDownloaded(order, filterOptions, limit, pinned)\n\n\tfun observeAll(\n\t\torder: ListSortOrder,\n\t\tfilterOptions: Set<ListFilterOption>,\n\t\tlimit: Int,\n\t\tpinned: List<Long> = emptyList(),\n\t): Flow<List<Manga>> {\n''',
)

# 4) The virtual Downloaded tab must call the global local-index observer. The ordinary Downloaded
# quick-filter in regular favourite categories still follows favourite membership.
vm = "app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/list/FavouritesListViewModel.kt"
replace_once(
    vm,
    '''\t\tval categoryFilters = if (categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID) {\n\t\t\tfilters + ListFilterOption.Downloaded\n\t\t} else {\n\t\t\tfilters\n\t\t}\n''',
    '''\t\tval categoryFilters = filters\n''',
)
replace_once(
    vm,
    '''\t\tif (categoryId == NO_ID || categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID) {\n\t\t\trepository.observeAll(queryOrder, categoryFilters, effectiveLimit, effectivePinned)\n\t\t} else {\n\t\t\trepository.observeAll(categoryId, queryOrder, categoryFilters, effectiveLimit, effectivePinned)\n\t\t}\n''',
    '''\t\tif (categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID) {\n\t\t\trepository.observeDownloaded(queryOrder, categoryFilters, effectiveLimit, effectivePinned)\n\t\t} else if (categoryId == NO_ID) {\n\t\t\trepository.observeAll(queryOrder, categoryFilters, effectiveLimit, effectivePinned)\n\t\t} else {\n\t\t\trepository.observeAll(categoryId, queryOrder, categoryFilters, effectiveLimit, effectivePinned)\n\t\t}\n''',
)

print("Downloaded category patch applied")
