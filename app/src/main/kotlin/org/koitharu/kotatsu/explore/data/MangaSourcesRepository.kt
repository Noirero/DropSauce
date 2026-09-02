package org.koitharu.kotatsu.explore.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.lnreader.LnPluginManager
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

/** Result of [MangaSourcesRepository.resolveActiveSource]. */
data class ResolvedSource(
	val source: MangaSource,
	val languageSubtitle: String?,
)

data class MihonSourceFilterEntry(
	val source: MihonMangaSource,
	val isEnabled: Boolean,
)

@Singleton
class MangaSourcesRepository @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val settings: AppSettings,
	private val mihonExtensionManager: MihonExtensionManager? = null,
	private val lnPluginManager: LnPluginManager? = null,
) {

	private val usageRefresh = MutableStateFlow(0)
	private val pinnedRefresh = MutableStateFlow(0)

	fun getEnabledSources(): List<MangaSource> {
		return buildSortedSourceInfoList(getAllEnabledSources()).map { it.mangaSource }
	}

	fun getPinnedSources(): Set<MangaSource> {
		val sourcesByKey = getAllEnabledSources().associateBy(::sourceKeyOf)
		return getPinnedSourceKeys()
			.mapNotNull { sourcesByKey[it] }
			.toSet()
	}

	fun getTopSources(limit: Int): List<MangaSource> {
		return getEnabledSources().take(limit)
	}

	fun observeEnabledSources(): Flow<List<MangaSourceInfo>> = combine(
		observeMihonSources(),
		observeLnSources(),
		settings.observeAsFlow(AppSettings.KEY_SOURCES_ORDER) { sourcesSortOrder },
		usageRefresh,
		pinnedRefresh,
	) { mihon, ln, _, _, _ ->
		buildSortedSourceInfoList(mihon + ln)
	}.distinctUntilChanged()

	fun observeAll(): Flow<List<Pair<MangaSource, Boolean>>> =
		combine(observeMihonSources(), observeLnSources()) { mihon, ln ->
			(mihon + ln).map { it to true }
		}

	fun setIsPinned(sources: Collection<MangaSource>, isPinned: Boolean): ReversibleHandle {
		val before = getPinnedSourceKeys()
		val updated = before.toMutableList()
		for (source in sources) {
			val key = sourceKeyOf(source)
			if (isPinned) {
				updated.remove(key)
				updated.add(0, key)
			} else {
				updated.remove(key)
			}
		}
		setPinnedSourceKeys(updated)
		pinnedRefresh.value++
		return ReversibleHandle {
			setPinnedSourceKeys(before)
			pinnedRefresh.value++
		}
	}

	/**
	 * Hides (or unhides) the extension packages backing [sources] from Explore. Returns a
	 * [ReversibleHandle] that restores the previous state.
	 */
	fun setSourcesHidden(sources: Collection<MangaSource>, hidden: Boolean): ReversibleHandle {
		val packages = sources.mapNotNullTo(HashSet()) { it.unwrapMihon()?.pkgName }
		val plugins = sources.mapNotNullTo(HashSet()) { it.unwrapLn()?.pluginId }
		val before = settings.mihonHiddenPackages
		val beforeLn = settings.lnHiddenPlugins
		settings.mihonHiddenPackages = if (hidden) before + packages else before - packages
		settings.lnHiddenPlugins = if (hidden) beforeLn + plugins else beforeLn - plugins
		return ReversibleHandle {
			settings.mihonHiddenPackages = before
			settings.lnHiddenPlugins = beforeLn
		}
	}

	private val usagePrefs: SharedPreferences by lazy {
		context.getSharedPreferences("source_usage", Context.MODE_PRIVATE)
	}

	fun trackUsage(source: MangaSource) {
		val key = sourceKeyOf(source)
		usagePrefs.edit().putLong(key, System.currentTimeMillis()).apply()
		usageRefresh.value++
	}

	private fun getLastUsedTimestamp(source: MangaSource): Long {
		val key = sourceKeyOf(source)
		return usagePrefs.getLong(key, 0L)
	}

	private val sourceStatePrefs: SharedPreferences by lazy {
		context.getSharedPreferences("source_state", Context.MODE_PRIVATE)
	}

	private fun sourceKeyOf(source: MangaSource): String = when (source) {
		is MangaSourceInfo -> sourceKeyOf(source.mangaSource)
		// Mihon source ids are the stable identity. Two language variants from one APK are independent.
		is MihonMangaSource -> "mihon:${source.sourceId}"
		is LnMangaSource -> "ln:${source.pluginId}"
		else -> {
			val matched = getMihonSources().firstOrNull { it.name == source.name }
			if (matched != null) {
				sourceKeyOf(matched)
			} else {
				source.name
			}
		}
	}

	private fun getPinnedSourceKeys(): List<String> {
		val raw = sourceStatePrefs.getString(KEY_PINNED_ORDER, null).orEmpty()
		if (raw.isEmpty()) return emptyList()
		return raw.split(PIN_SEPARATOR).filter { it.isNotBlank() }
	}

	private fun setPinnedSourceKeys(keys: List<String>) {
		sourceStatePrefs.edit().putString(KEY_PINNED_ORDER, keys.joinToString(PIN_SEPARATOR)).apply()
	}

	private fun buildSortedSourceInfoList(sources: List<MangaSource>): List<MangaSourceInfo> {
		if (sources.isEmpty()) return emptyList()
		val pinnedOrder = normalizeLegacyPinnedSourceKeys(getPinnedSourceKeys(), sources)
		val pinnedIndex = HashMap<String, Int>(pinnedOrder.size)
		for ((index, key) in pinnedOrder.withIndex()) {
			pinnedIndex[key] = index
		}

		val pinned = ArrayList<MangaSourceInfo>()
		val unpinned = ArrayList<MangaSourceInfo>()
		for (source in sources) {
			val isPinned = sourceKeyOf(source) in pinnedIndex
			val item = MangaSourceInfo(source, isEnabled = true, isPinned = isPinned)
			if (isPinned) pinned += item else unpinned += item
		}

		pinned.sortBy { pinnedIndex[sourceKeyOf(it.mangaSource)] ?: Int.MAX_VALUE }
		when (settings.sourcesSortOrder) {
			SourcesSortOrder.ALPHABETIC -> unpinned.sortWith(compareBy { it.getTitle(context) })
			SourcesSortOrder.LAST_USED -> unpinned.sortWith(compareByDescending { getLastUsedTimestamp(it.mangaSource) })
			SourcesSortOrder.MANUAL -> Unit
		}
		return pinned + unpinned
	}

	/** Returns every enabled Mihon source independently. No name/language collapsing is allowed. */
	private fun getMihonSources(): List<MihonMangaSource> {
		val manager = mihonExtensionManager ?: return emptyList()
		manager.initialize()
		val hideNsfw = settings.isNsfwContentDisabled
		val hiddenPackages = settings.mihonHiddenPackages
		val disabledSourceIds = settings.mihonDisabledSourceIds
		return manager.getMihonMangaSources()
			.filterNot { hideNsfw && it.isNsfw }
			.filterNot { it.pkgName in hiddenPackages }
			.filterNot { it.sourceId.toString() in disabledSourceIds }
	}

	/**
	 * Resolves a stale wrapper by its exact source id. Language variants must never redirect to a
	 * sibling after restart; favourites/history depend on this preserving the original source id.
	 */
	fun resolveActiveSource(source: MangaSource): ResolvedSource {
		val mihon = source.unwrapMihon() ?: return ResolvedSource(source, null)
		val manager = mihonExtensionManager ?: return ResolvedSource(source, null)
		manager.initialize()
		val exact = manager.getMihonMangaSourceById(mihon.sourceId) ?: mihon
		return ResolvedSource(exact, exact.languageDisplayName)
	}

	fun setMihonSourcesEnabled(sourceIds: Collection<Long>, enabled: Boolean) {
		val updated = settings.mihonDisabledSourceIds.toMutableSet()
		for (sourceId in sourceIds) {
			if (enabled) updated.remove(sourceId.toString()) else updated.add(sourceId.toString())
		}
		settings.mihonDisabledSourceIds = updated
	}

	fun setMihonLanguageEnabled(language: String, enabled: Boolean) {
		val ids = getAllMihonSources().filter {
			it.language.equals(language, ignoreCase = true) ||
				(language.equals("other", ignoreCase = true) && it.language.isBlank())
		}.map { it.sourceId }
		setMihonSourcesEnabled(ids, enabled)
	}

	/** Novel plugins mixed straight into Explore alongside manga sources. */
	fun getLnSources(): List<LnMangaSource> {
		val manager = lnPluginManager ?: return emptyList()
		manager.initialize()
		val hidden = settings.lnHiddenPlugins
		return manager.getAll().filterNot { it.pluginId in hidden }
	}

	fun observeLnSources(): Flow<List<LnMangaSource>> {
		val manager = lnPluginManager ?: return kotlinx.coroutines.flow.flowOf(emptyList())
		manager.initialize()
		return combine(
			manager.sources,
			settings.observeAsFlow(AppSettings.KEY_LN_HIDDEN_PLUGINS) { lnHiddenPlugins },
		) { _: Any?, _: Any? ->
			getLnSources()
		}.distinctUntilChanged()
	}

	private fun getAllEnabledSources(): List<MangaSource> = getMihonSources() + getLnSources()

	private fun MangaSource.unwrapLn(): LnMangaSource? = when (this) {
		is LnMangaSource -> this
		is MangaSourceInfo -> mangaSource as? LnMangaSource
		else -> null
	}

	private fun MangaSource.unwrapMihon(): MihonMangaSource? = when (this) {
		is MihonMangaSource -> this
		is MangaSourceInfo -> mangaSource as? MihonMangaSource
		else -> null
	}

	/** True when at least one installed source offers more than one language. */
	private fun hasMultiLanguageSources(): Boolean {
		val manager = mihonExtensionManager ?: return false
		return manager.getMihonMangaSources()
			.groupBy { it.pkgName to it.catalogueSource.name }
			.any { (_, group) -> group.mapTo(HashSet()) { it.language }.size > 1 }
	}

	private fun getAllMihonSources(): List<MihonMangaSource> {
		val manager = mihonExtensionManager ?: return emptyList()
		manager.initialize()
		val sources = manager.getMihonMangaSources()
		val hideNsfw = settings.isNsfwContentDisabled
		return sources.filter { source ->
			!hideNsfw || !source.isNsfw
		}
	}

	fun observeMihonSources(): Flow<List<MihonMangaSource>> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(emptyList())
		manager.initialize()
		return combine(
			manager.installedExtensions,
			manager.isLoading,
			settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
			settings.observeAsFlow(AppSettings.KEY_MIHON_HIDDEN_PACKAGES) { mihonHiddenPackages },
			settings.observeAsFlow(AppSettings.KEY_MIHON_DISABLED_SOURCE_IDS) { mihonDisabledSourceIds },
		) { _: Any?, _: Any?, _: Any?, _: Any?, _: Any? ->
			getMihonSources()
		}.distinctUntilChanged()
	}

	fun observeMihonSourceFilters(): Flow<List<MihonSourceFilterEntry>> {
		return combine(
			observeAllMihonSources(),
			settings.observeAsFlow(AppSettings.KEY_MIHON_DISABLED_SOURCE_IDS) { mihonDisabledSourceIds },
		) { sources, disabled ->
			sources.map { MihonSourceFilterEntry(it, it.sourceId.toString() !in disabled) }
		}.distinctUntilChanged()
	}

	/** Emits `true` while any installed source offers more than one language. */
	fun observeHasMultiLanguageSources(): Flow<Boolean> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(false)
		manager.initialize()
		return combine(
			manager.installedExtensions,
			manager.isLoading,
		) { _: Any?, _: Any? ->
			hasMultiLanguageSources()
		}.distinctUntilChanged()
	}

	/** Emits `true` while the Mihon extension manager is loading extensions, `false` otherwise. */
	fun observeMihonLoadingState(): Flow<Boolean> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(false)
		return manager.isLoading
	}

	fun observeAllMihonSources(): Flow<List<MihonMangaSource>> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(emptyList())
		manager.initialize()
		return combine(
			manager.installedExtensions,
			manager.isLoading,
			settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
		) { _: Any?, _: Any?, _: Any? ->
			getAllMihonSources()
		}.distinctUntilChanged()
	}

	suspend fun reloadMihonSources() {
		mihonExtensionManager?.loadExtensions()
	}

	/** Waits for the first extension scan so one-shot searches never capture an empty cold-start list. */
	suspend fun ensureExternalSourcesReady() {
		mihonExtensionManager?.ensureReady()
		lnPluginManager?.initialize()
	}

	private fun normalizeLegacyPinnedSourceKeys(keys: List<String>, sources: List<MangaSource>): List<String> {
		var changed = false
		val normalized = keys.mapNotNull { key ->
			if (!key.startsWith("mihon:") || key.removePrefix("mihon:").toLongOrNull() != null) return@mapNotNull key
			val legacy = key.removePrefix("mihon:")
			val matches = sources.filterIsInstance<MihonMangaSource>().filter {
				legacy == "${it.pkgName}:${it.catalogueSource.name}"
			}
			val match = matches.firstOrNull { candidate ->
				settings.getMihonActiveLang(candidate.pkgName, candidate.catalogueSource.name)
					?.equals(candidate.language, ignoreCase = true) == true
			} ?: matches.firstOrNull()
			changed = true
			match?.let(::sourceKeyOf)
		}.distinct()
		if (changed) setPinnedSourceKeys(normalized)
		return normalized
	}

	private companion object {
		private const val KEY_PINNED_ORDER = "pinned_order"
		private const val PIN_SEPARATOR = "\n"
	}
}
