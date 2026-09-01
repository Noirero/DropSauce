package org.koitharu.kotatsu.mihon

import androidx.core.net.toUri
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.parsers.model.MangaPage

/**
 * Fetches an image with a byte offset when the repository is backed by a real Mihon HttpSource.
 * Newer sources can use the resumable API directly. If an older extension overrides the legacy
 * getImage(Page) hook (commonly for decrypt/unscramble work), we deliberately call that override
 * instead of bypassing its transform; the worker then sees HTTP 200 and safely restarts that page.
 */
suspend fun MangaRepository.getResumableImageStream(
	pageUrl: String,
	page: MangaPage,
	existingSize: Long,
): Response? {
	if (existingSize <= 0L) return getImageStream(pageUrl, page)
	val mihonRepository = this as? MihonMangaRepository ?: return getImageStream(pageUrl, page)
	if (mihonRepository.source.isNovel) return getImageStream(pageUrl, page)
	val httpSource = mihonRepository.mihonSource as? HttpSource ?: return getImageStream(pageUrl, page)
	val mihonPage = page.toResumeMihonPage(pageUrl)
	return withContext(Dispatchers.IO) {
		try {
			when {
				httpSource.hasResumableImageOverride() -> httpSource.getImage(mihonPage, existingSize)
				httpSource.hasLegacyImageOverride() -> httpSource.getImage(mihonPage)
				else -> httpSource.getImage(mihonPage, existingSize)
			}
		} catch (e: HttpException) {
			if (e.code != HTTP_RANGE_NOT_SATISFIABLE) throw e
			// The remote object changed (or the partial already equals its full size). A clean
			// request lets DownloadWorker overwrite the stale .part instead of failing the chapter.
			httpSource.getImage(mihonPage)
		}
	}
}

private fun HttpSource.hasLegacyImageOverride(): Boolean = javaClass.methods.any { method ->
	method.name == "getImage" &&
		method.parameterTypes.size == 2 &&
		method.parameterTypes.firstOrNull() == Page::class.java &&
		method.declaringClass != HttpSource::class.java
}

private fun HttpSource.hasResumableImageOverride(): Boolean = javaClass.methods.any { method ->
	method.name == "getImage" &&
		method.parameterTypes.size == 3 &&
		method.parameterTypes.firstOrNull() == Page::class.java &&
		method.declaringClass != HttpSource::class.java
}

private fun MangaPage.toResumeMihonPage(imageUrl: String): Page {
	val ref = url.toMihonResumeRef()
	return Page(
		index = ref?.index ?: 0,
		url = ref?.pageUrl ?: imageUrl,
		imageUrl = imageUrl,
	)
}

private fun String.toMihonResumeRef(): MihonResumeRef? {
	val uri = runCatching { toUri() }.getOrNull() ?: return null
	if (uri.scheme != "mihon") return null
	val pageUrl = uri.getQueryParameter("page_url") ?: return null
	return MihonResumeRef(
		pageUrl = pageUrl,
		index = uri.getQueryParameter("index")?.toIntOrNull() ?: 0,
	)
}

private data class MihonResumeRef(
	val pageUrl: String,
	val index: Int,
)

private const val HTTP_RANGE_NOT_SATISFIABLE = 416
