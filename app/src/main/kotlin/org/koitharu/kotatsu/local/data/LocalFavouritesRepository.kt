package org.koitharu.kotatsu.local.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.util.AlphanumComparator
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backing store for the system "Lokal" favourites category.
 *
 * The category is intentionally virtual: nothing is written to the favourites database. Its items
 * are reconstructed from `<configured manga root>/local/<title>/` and therefore disappear naturally
 * when their files are removed. Folder-name matching is case-insensitive so local/Local/LOCAL are
 * treated the same.
 *
 * EPUB is deliberately excluded for now. A title folder is eligible only when it contains at least
 * one direct CBZ/ZIP chapter and no direct EPUB file, keeping this first implementation manga-only.
 */
@Singleton
class LocalFavouritesRepository @Inject constructor(
	private val storageManager: LocalStorageManager,
) {

	private val mutex = Mutex()
	private val _items = MutableStateFlow<List<Manga>>(emptyList())

	val items: StateFlow<List<Manga>> = _items.asStateFlow()

	suspend fun refresh() = mutex.withLock {
		val roots = storageManager.getReadableDirs()
		val mangaFolders = runInterruptible(Dispatchers.IO) {
			findMangaFolders(roots)
		}

		val parsed = ArrayList<Manga>(mangaFolders.size)
		for (folder in mangaFolders) {
			runCatchingCancellable {
				LocalMangaParser.getOrNull(folder)?.getManga(withDetails = false)?.manga
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrNull()?.let(parsed::add)
		}

		_items.value = parsed
			.distinctBy { it.url }
			.sortedWith(compareBy(AlphanumComparator()) { it.title })
	}

	private fun findMangaFolders(roots: List<File>): List<File> {
		val result = LinkedHashMap<String, File>()
		for (root in roots) {
			val localRoots = ArrayList<File>()
			if (root.isDirectory && root.name.equals(LOCAL_FOLDER_NAME, ignoreCase = true)) {
				localRoots += root
			}
			root.listFiles()?.filterTo(localRoots) {
				it.isDirectory && it.name.equals(LOCAL_FOLDER_NAME, ignoreCase = true)
			}

			for (localRoot in localRoots) {
				localRoot.listFiles()?.forEach { mangaFolder ->
					if (
						mangaFolder.isDirectory &&
						!mangaFolder.isHidden &&
						mangaFolder.hasSupportedArchiveChapters()
					) {
						result.putIfAbsent(mangaFolder.absolutePath, mangaFolder)
					}
				}
			}
		}
		return result.values.toList()
	}

	private fun File.hasSupportedArchiveChapters(): Boolean {
		var hasCbzOrZip = false
		for (file in listFiles().orEmpty()) {
			if (!file.isFile) continue
			when {
				file.extension.equals("epub", ignoreCase = true) -> return false
				file.extension.equals("cbz", ignoreCase = true) ||
					file.extension.equals("zip", ignoreCase = true) -> hasCbzOrZip = true
			}
		}
		return hasCbzOrZip
	}

	private companion object {
		const val LOCAL_FOLDER_NAME = "local"
	}
}
