package org.koitharu.kotatsu.mihon

import androidx.core.net.toUri
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.parsers.model.MangaPage

/**
 * Fetches an image with a byte offset when the repository is backed by a real Mihon HttpSource.
 * Using HttpSource.getImage(page, existingSize) is important: it keeps extension-specific image
 * transforms/decryption and headers while also allowing HTTP Range resume. Repositories that do
 * not expose a Mihon HttpSource fall back to their normal image path and the worker will safely
 * overwrite the partial file if the response is HTTP 200 rather than 206.
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
	return withContext(Dispatchers.IO) {
		httpSource.getImage(page.toResumeMihonPage(pageUrl), existingSize)
	}
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
