from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HELPER_PATH = ROOT / ".github" / "workflows" / "beta-build-once.yml"
MANUAL_HELPER = """name: Beta Build Once

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
"""


def replace_exact(text: str, old: str, new: str, label: str, expected: int = 1) -> str:
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"{label}: expected {expected} occurrence(s), found {count}")
    return text.replace(old, new)


def patch_extension_languages(repo: Path) -> None:
    path = repo / "app/src/main/kotlin/org/koitharu/kotatsu/settings/sources/catalog/SourcesCatalogViewModel.kt"
    text = path.read_text()

    text = replace_exact(
        text,
        "import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageDisplayName\n",
        "import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLangCode\n"
        "import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageDisplayName\n",
        "language normalizer import",
    )
    text = replace_exact(
        text,
        "\tprivate val availableRepoEntries = MutableStateFlow<List<ExternalExtensionRepoEntry>>(emptyList())\n",
        "",
        "obsolete per-page language cache",
    )

    start = text.index("\tval locales: StateFlow<Set<String?>> = combine(")
    end = text.index("\n\n\tval contentTypes:", start)
    locales_block = (
        "\tval locales: StateFlow<Set<String?>> = combine(\n"
        "\t\tallMihonSources,\n"
        "\t\tstoreManager.states,\n"
        "\t\tisNsfwDisabled,\n"
        "\t\trefreshTrigger,\n"
        "\t) { sources, storeStates, nsfwDisabled, _ ->\n"
        "\t\tval localeSet = LinkedHashSet<String?>()\n"
        "\t\tsources.forEach { localeSet.addCatalogLanguage(it.language) }\n"
        "\t\tfor (state in storeStates) {\n"
        "\t\t\tfor (entry in state.catalog) {\n"
        "\t\t\t\tif (nsfwDisabled && entry.isNsfw != 0) continue\n"
        "\t\t\t\tlocaleSet.addCatalogLanguage(entry.lang)\n"
        "\t\t\t\tentry.sources.forEach { localeSet.addCatalogLanguage(it.lang) }\n"
        "\t\t\t}\n"
        "\t\t}\n"
        "\t\tlnPluginManager.getAll().forEach { localeSet.addCatalogLanguage(it.plugin.langCode) }\n"
        "\t\tlocaleSet.add(null)\n"
        "\t\tlocaleSet\n"
        "\t}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, defaultLocales)"
    )
    text = text[:start] + locales_block + text[end:]

    assignment = "\t\tavailableRepoEntries.value = available\n"
    if text.count(assignment) != 2:
        raise RuntimeError(f"per-page language cache assignments: expected 2, found {text.count(assignment)}")
    text = text.replace(assignment, "")

    old_entry_filter = "if (locale != null && entry.lang != locale) continue"
    entry_filter_count = text.count(old_entry_filter)
    if entry_filter_count < 2:
        raise RuntimeError(f"entry language filters: expected at least 2, found {entry_filter_count}")
    text = text.replace(
        old_entry_filter,
        "if (!extensionEntryMatchesLanguage("
        "entry, allInstalledSourcesByPkg[entry.packageName] ?: installedSourcesByPkg[entry.packageName], locale)) continue",
    )

    old_plugin_filter = "if (filter.locale != null && plugin.langCode != filter.locale) continue"
    plugin_filter_count = text.count(old_plugin_filter)
    if plugin_filter_count < 2:
        raise RuntimeError(f"plugin language filters: expected at least 2, found {plugin_filter_count}")
    text = text.replace(
        old_plugin_filter,
        "if (filter.locale != null && !extensionLanguageMatches(plugin.langCode, filter.locale)) continue",
    )

    helper_start = text.index("internal fun installedExtensionMatchesLanguage(")
    helper_end = text.index("\nprivate fun sameLanguageCode", helper_start)
    helpers = (
        'private val NON_FILTER_LANGUAGE_CODES = setOf("all", "mul", "other", "none", "und")\n\n'
        "private fun MutableSet<String?>.addCatalogLanguage(language: String?) {\n"
        "\tval normalized = language?.let(::getExternalExtensionLangCode)?.takeIf { it.isNotBlank() } ?: return\n"
        "\tif (normalized.lowercase() in NON_FILTER_LANGUAGE_CODES) return\n"
        "\tadd(normalized)\n"
        "}\n\n"
        "internal fun installedExtensionMatchesLanguage(\n"
        "\tsources: List<org.koitharu.kotatsu.mihon.model.MihonMangaSource>?,\n"
        "\tfallbackLanguage: String,\n"
        "\tselectedLanguage: String?,\n"
        "): Boolean {\n"
        "\tif (selectedLanguage == null) return true\n"
        "\treturn if (sources.isNullOrEmpty()) {\n"
        "\t\textensionLanguageMatches(fallbackLanguage, selectedLanguage)\n"
        "\t} else {\n"
        "\t\tsources.any { extensionLanguageMatches(it.language, selectedLanguage) }\n"
        "\t}\n"
        "}\n\n"
        "internal fun extensionEntryMatchesLanguage(\n"
        "\tentry: ExternalExtensionRepoEntry,\n"
        "\tinstalledSources: List<org.koitharu.kotatsu.mihon.model.MihonMangaSource>?,\n"
        "\tselectedLanguage: String?,\n"
        "): Boolean {\n"
        "\tif (selectedLanguage == null) return true\n"
        "\tif (installedSources.orEmpty().any { extensionLanguageMatches(it.language, selectedLanguage) }) return true\n"
        "\tif (entry.sources.any { source -> source.lang?.let { extensionLanguageMatches(it, selectedLanguage) } == true }) return true\n"
        "\treturn entry.lang?.let { extensionLanguageMatches(it, selectedLanguage) } == true\n"
        "}\n\n"
        "private fun extensionLanguageMatches(candidate: String, selected: String): Boolean {\n"
        "\tval normalizedCandidate = getExternalExtensionLangCode(candidate)\n"
        "\tval normalizedSelected = getExternalExtensionLangCode(selected)\n"
        '\tif (normalizedCandidate.equals("all", ignoreCase = true) || normalizedCandidate.equals("mul", ignoreCase = true)) {\n'
        "\t\treturn true\n"
        "\t}\n"
        "\treturn normalizedCandidate.equals(normalizedSelected, ignoreCase = true)\n"
        "}\n"
    )
    text = text[:helper_start] + helpers + text[helper_end:]
    path.write_text(text)


def patch_favourites_query(repo: Path) -> None:
    path = repo / "app/src/main/kotlin/org/koitharu/kotatsu/favourites/data/FavouritesDao.kt"
    text = path.read_text()
    text = replace_exact(
        text,
        '\t\t\t.filters(filterOptions)\n\t\t\t.groupBy("favourites.manga_id")\n\t\t\t.orderBy(getOrderBy(order, pinned))',
        '\t\t\t.filters(filterOptions)\n'
        "\t\t\t.apply {\n"
        "\t\t\t\tif (categoryId == 0L) {\n"
        '\t\t\t\t\tgroupBy("favourites.manga_id")\n'
        "\t\t\t\t}\n"
        "\t\t\t}\n"
        "\t\t\t.orderBy(getOrderBy(order, pinned))",
        "conditional favourites grouping",
    )
    path.write_text(text)


def patch_chapter_aggregates(repo: Path) -> None:
    path = repo / "app/src/main/kotlin/org/koitharu/kotatsu/core/db/dao/ChaptersDao.kt"
    text = path.read_text()
    marker = "@Dao\nabstract class ChaptersDao {"
    projections = (
        "data class ChapterLogicalCount(\n"
        "\tval mangaId: Long,\n"
        "\tval chapterCount: Int,\n"
        ")\n\n"
        "data class ChapterUnreadAfterCurrent(\n"
        "\tval mangaId: Long,\n"
        "\tval chapterId: Long,\n"
        "\tval unreadCount: Int,\n"
        ")\n\n"
        "@Dao\n"
        "abstract class ChaptersDao {"
    )
    text = replace_exact(text, marker, projections, "chapter aggregate projections")

    find_all = (
        '\t@Query("SELECT * FROM chapters WHERE manga_id IN (:mangaIds) ORDER BY manga_id, `index` ASC")\n'
        "\tabstract suspend fun findAll(mangaIds: Collection<Long>): List<ChapterEntity>\n"
    )
    aggregate_queries = find_all + (
        "\n"
        "\t@Query(\n"
        '\t\t"""\n'
        "\t\tSELECT manga_id AS mangaId, MAX(branch_count) AS chapterCount\n"
        "\t\tFROM (\n"
        "\t\t\tSELECT manga_id, branch, COUNT(*) AS branch_count\n"
        "\t\t\tFROM chapters\n"
        "\t\t\tWHERE manga_id IN (:mangaIds)\n"
        "\t\t\tGROUP BY manga_id, branch\n"
        "\t\t) AS branch_counts\n"
        "\t\tGROUP BY manga_id\n"
        '\t\t""",\n'
        "\t)\n"
        "\tabstract suspend fun findLogicalCounts(mangaIds: Collection<Long>): List<ChapterLogicalCount>\n\n"
        "\t@Query(\n"
        '\t\t"""\n'
        "\t\tSELECT current.manga_id AS mangaId,\n"
        "\t\t\tcurrent.chapter_id AS chapterId,\n"
        "\t\t\t(\n"
        "\t\t\t\tSELECT COUNT(*)\n"
        "\t\t\t\tFROM chapters AS candidate\n"
        "\t\t\t\tWHERE candidate.manga_id = current.manga_id\n"
        "\t\t\t\t\tAND candidate.branch IS current.branch\n"
        "\t\t\t\t\tAND candidate.`index` > current.`index`\n"
        "\t\t\t) AS unreadCount\n"
        "\t\tFROM chapters AS current\n"
        "\t\tWHERE current.manga_id IN (:mangaIds)\n"
        "\t\t\tAND current.chapter_id IN (:chapterIds)\n"
        '\t\t""",\n'
        "\t)\n"
        "\tabstract suspend fun findUnreadAfterCurrent(\n"
        "\t\tmangaIds: Collection<Long>,\n"
        "\t\tchapterIds: Collection<Long>,\n"
        "\t): List<ChapterUnreadAfterCurrent>\n"
    )
    text = replace_exact(text, find_all, aggregate_queries, "chapter aggregate queries")
    path.write_text(text)


def patch_unread_counter(repo: Path) -> None:
    path = repo / "app/src/main/kotlin/org/koitharu/kotatsu/favourites/domain/FavouriteUnreadCounter.kt"
    text = path.read_text()
    text = replace_exact(
        text,
        "import org.koitharu.kotatsu.core.db.entity.ChapterEntity\n",
        "",
        "unused full chapter entity import",
    )
    start = text.index("\tsuspend fun getSnapshot(")
    end = text.index("\n\tprivate companion object {", start)
    implementation = (
        "\tsuspend fun getSnapshot(mangaIds: Collection<Long>, includeUnread: Boolean): Snapshot {\n"
        "\t\tif (mangaIds.isEmpty()) return Snapshot(emptyMap(), emptyMap())\n"
        "\t\tval ids = mangaIds.distinct()\n"
        "\t\tval historyDao = database.getHistoryDao()\n"
        "\t\tval histories = ids.chunked(DB_QUERY_BATCH_SIZE)\n"
        "\t\t\t.flatMap { historyDao.findByIds(it) }\n"
        "\t\t\t.associateBy { it.mangaId }\n"
        "\t\tif (!includeUnread) return Snapshot(histories, emptyMap())\n\n"
        "\t\tval chaptersDao = database.getChaptersDao()\n"
        "\t\tval logicalCounts = ids.chunked(DB_QUERY_BATCH_SIZE)\n"
        "\t\t\t.flatMap { chaptersDao.findLogicalCounts(it) }\n"
        "\t\t\t.associate { it.mangaId to it.chapterCount }\n"
        "\t\tval exactUnread = histories.values\n"
        "\t\t\t.chunked(DB_HISTORY_QUERY_BATCH_SIZE)\n"
        "\t\t\t.flatMap { chunk ->\n"
        "\t\t\t\tchaptersDao.findUnreadAfterCurrent(\n"
        "\t\t\t\t\tmangaIds = chunk.map { it.mangaId },\n"
        "\t\t\t\t\tchapterIds = chunk.map { it.chapterId },\n"
        "\t\t\t\t)\n"
        "\t\t\t}\n"
        "\t\t\t.associateBy { it.mangaId to it.chapterId }\n\n"
        "\t\tval unread = HashMap<Long, Int>(ids.size)\n"
        "\t\tfor (mangaId in ids) {\n"
        "\t\t\tval logicalCount = logicalCounts[mangaId] ?: 0\n"
        "\t\t\tval history = histories[mangaId]\n"
        "\t\t\tunread[mangaId] = when {\n"
        "\t\t\t\thistory == null -> logicalCount\n"
        "\t\t\t\tlogicalCount <= 0 -> 0\n"
        "\t\t\t\telse -> exactUnread[mangaId to history.chapterId]?.unreadCount\n"
        "\t\t\t\t\t?: calculateFallbackUnread(logicalCount, history)\n"
        "\t\t\t}\n"
        "\t\t}\n"
        "\t\treturn Snapshot(histories, unread)\n"
        "\t}\n\n"
        "\tsuspend fun getUnreadCount(mangaId: Long): Int =\n"
        "\t\tgetSnapshot(listOf(mangaId), includeUnread = true).unreadCounts[mangaId] ?: 0\n\n"
        "\tprivate fun calculateFallbackUnread(logicalCount: Int, history: HistoryEntity): Int {\n"
        "\t\tval total = history.chaptersCount.takeIf { it > 0 } ?: logicalCount\n"
        "\t\tif (total <= 0) return 0\n"
        "\t\tval read = ceil(history.percent.coerceIn(0f, 1f).toDouble() * total.toDouble())\n"
        "\t\t\t.toInt()\n"
        "\t\t\t.coerceIn(0, total)\n"
        "\t\treturn (total - read).coerceAtLeast(0)\n"
        "\t}\n"
    )
    text = text[:start] + implementation + text[end:]
    text = replace_exact(
        text,
        "\t\tconst val DB_QUERY_BATCH_SIZE = 500\n",
        "\t\tconst val DB_QUERY_BATCH_SIZE = 500\n"
        "\t\tconst val DB_HISTORY_QUERY_BATCH_SIZE = 400\n",
        "history query batch size",
    )
    path.write_text(text)


def patch_tests(repo: Path) -> None:
    path = repo / "app/src/test/kotlin/org/koitharu/kotatsu/settings/sources/catalog/InstalledExtensionLanguageFilterTest.kt"
    text = path.read_text()
    marker = "\tprivate fun source(sourceId: Long, language: String) = MihonMangaSource(\n"
    tests = (
        "\t@Test\n"
        "\tfun `universal package language remains eligible for a specific language`() {\n"
        '\t\tassertTrue(installedExtensionMatchesLanguage(emptyList(), "all", "ja"))\n'
        "\t}\n\n"
        "\t@Test\n"
        "\tfun `uninstalled multi-source entry matches every published source language`() {\n"
        "\t\tval entry = ExternalExtensionRepoEntry(\n"
        '\t\t\tname = "Multi",\n'
        '\t\t\tpackageName = "extension.multi",\n'
        '\t\t\tapkName = "multi.apk",\n'
        '\t\t\tlang = "all",\n'
        "\t\t\tversionCode = 1,\n"
        '\t\t\tversionName = "1.0",\n'
        "\t\t\tsources = listOf(\n"
        '\t\t\t\tExternalExtensionRepoSource(id = "1", name = "Japanese", lang = "ja"),\n'
        '\t\t\t\tExternalExtensionRepoSource(id = "2", name = "Chinese", lang = "zh"),\n'
        "\t\t\t),\n"
        "\t\t)\n\n"
        '\t\tassertTrue(extensionEntryMatchesLanguage(entry, emptyList(), "ja"))\n'
        '\t\tassertTrue(extensionEntryMatchesLanguage(entry, emptyList(), "ZH"))\n'
        "\t\t// Older repositories can expose only a universal package language without per-source metadata.\n"
        '\t\tassertTrue(extensionEntryMatchesLanguage(entry.copy(sources = emptyList()), emptyList(), "ko"))\n'
        "\t}\n\n"
    )
    text = replace_exact(text, marker, tests + marker, "extension language regression tests")
    path.write_text(text)


def apply(repo: Path) -> None:
    patch_extension_languages(repo)
    patch_favourites_query(repo)
    patch_chapter_aggregates(repo)
    patch_unread_counter(repo)
    patch_tests(repo)


def finalize() -> None:
    HELPER_PATH.write_text(MANUAL_HELPER)
    Path(__file__).unlink()


def main() -> None:
    if len(sys.argv) != 2 or sys.argv[1] not in {"apply", "finalize"}:
        raise SystemExit("usage: source-fix-once.py apply|finalize")
    if sys.argv[1] == "apply":
        apply(ROOT)
    else:
        finalize()


if __name__ == "__main__":
    main()
