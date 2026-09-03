from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:120]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"Expected exactly one match in {path}, got {text.count(old)}")
    p.write_text(text.replace(old, new, 1))


dao = "app/src/main/kotlin/org/koitharu/kotatsu/favourites/data/FavouritesDao.kt"
replace_once(
    dao,
    '''\t@Query(\n\t\t"SELECT manga.manga_id AS manga_id, manga.title AS title, manga.author AS author, manga.source AS source " +\n\t\t\t"FROM local_index INNER JOIN manga ON manga.manga_id = local_index.manga_id",\n\t)\n\tabstract suspend fun findDownloadedSearchEntries(): List<FavouriteSearchEntry>\n\n\t@Query(\n\t\t"SELECT manga.source AS source, COUNT(DISTINCT local_index.manga_id) AS item_count " +\n\t\t\t"FROM local_index INNER JOIN manga ON manga.manga_id = local_index.manga_id " +\n\t\t\t"GROUP BY manga.source",\n\t)\n\tabstract suspend fun findDownloadedCountsBySource(): List<FavouriteSourceCount>\n''',
    '''\t@Query(\n\t\t"SELECT manga.manga_id AS manga_id, manga.title AS title, manga.author AS author, manga.source AS source " +\n\t\t\t"FROM local_index INNER JOIN manga ON manga.manga_id = local_index.manga_id " +\n\t\t\t"WHERE manga.source != 'LOCAL' OR local_index.path LIKE '%/downloads/%'",\n\t)\n\tabstract suspend fun findDownloadedSearchEntries(): List<FavouriteSearchEntry>\n\n\t@Query(\n\t\t"SELECT manga.source AS source, COUNT(DISTINCT local_index.manga_id) AS item_count " +\n\t\t\t"FROM local_index INNER JOIN manga ON manga.manga_id = local_index.manga_id " +\n\t\t\t"WHERE manga.source != 'LOCAL' OR local_index.path LIKE '%/downloads/%' " +\n\t\t\t"GROUP BY manga.source",\n\t)\n\tabstract suspend fun findDownloadedCountsBySource(): List<FavouriteSourceCount>\n''',
)
replace_once(
    dao,
    '''\t): Flow<List<MangaWithTags>> = observeDownloadedImpl(\n\t\tMangaQueryBuilder("manga", ::getDownloadedCondition)\n\t\t\t.join("INNER JOIN local_index ON local_index.manga_id = manga.manga_id")\n\t\t\t.filters(filterOptions - ListFilterOption.Downloaded)\n''',
    '''\t): Flow<List<MangaWithTags>> = observeDownloadedImpl(\n\t\tMangaQueryBuilder("manga", ::getDownloadedCondition)\n\t\t\t.join("INNER JOIN local_index ON local_index.manga_id = manga.manga_id")\n\t\t\t.where("manga.source != 'LOCAL' OR local_index.path LIKE '%/downloads/%'")\n\t\t\t.filters(filterOptions - ListFilterOption.Downloaded)\n''',
)

vm = "app/src/main/kotlin/org/koitharu/kotatsu/favourites/ui/list/FavouritesListViewModel.kt"
replace_once(
    vm,
    '''\t\tval candidates = if (display.fromBottom) windowed.asReversed() else windowed\n\t\tval typed = candidates.filter { it.source.isNovelSource == wantNovel }\n\t\tval searched = searchMatcher.filter(typed, display.query)\n''',
    '''\t\tval candidates = if (display.fromBottom) windowed.asReversed() else windowed\n\t\tval typed = candidates.filter { manga ->\n\t\t\tval isNovel = if (categoryId == DOWNLOADED_FAVOURITES_CATEGORY_ID && manga.source.isLocal) {\n\t\t\t\tval normalizedUrl = manga.url.replace('\\\\', '/')\n\t\t\t\tnormalizedUrl.contains("/00.Novel/", ignoreCase = true) ||\n\t\t\t\t\tnormalizedUrl.substringBefore('#').substringBefore('?').endsWith(".epub", ignoreCase = true)\n\t\t\t} else {\n\t\t\t\tmanga.source.isNovelSource\n\t\t\t}\n\t\t\tisNovel == wantNovel\n\t\t}\n\t\tval searched = searchMatcher.filter(typed, display.query)\n''',
)

print("Downloaded category refinement applied")
