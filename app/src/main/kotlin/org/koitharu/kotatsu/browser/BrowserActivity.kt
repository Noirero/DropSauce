package org.koitharu.kotatsu.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.InteractiveActionRequiredException
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.nullIfEmpty

@AndroidEntryPoint
class BrowserActivity : BaseBrowserActivity() {

	private var successCookieUrl: String? = null
	private var successCookieName: String? = null
	private var initialCookieValue: String? = null
	private var sourceHeaders: Map<String, String> = emptyMap()

	override fun onCreate2(savedInstanceState: Bundle?, source: MangaSource, repository: MangaRepository?) {
		successCookieUrl = intent?.getStringExtra(AppRouter.KEY_SUCCESS_COOKIE_URL)
		successCookieName = intent?.getStringExtra(AppRouter.KEY_SUCCESS_COOKIE_NAME)
		if (successCookieUrl != null && successCookieName != null) {
			initialCookieValue = getCookieValue(successCookieUrl!!, successCookieName!!)
		}

		// Mihon opens a source WebView with the source's own HttpSource headers. Some sites bind
		// authenticated sessions / anti-bot cookies to those headers (especially User-Agent), so a
		// generic WebView session may successfully log in but still be rejected by chapter requests.
		sourceHeaders = getSourceHeaders(source)
		val explicitUserAgent = intent?.getStringExtra(AppRouter.KEY_USER_AGENT)?.nullIfEmpty()
		val sourceUserAgent = sourceHeaders.entries
			.firstOrNull { it.key.equals("user-agent", ignoreCase = true) }
			?.value
			?.nullIfEmpty()
		val effectiveUserAgent = explicitUserAgent ?: sourceUserAgent
		if (effectiveUserAgent != null) {
			viewBinding.webView.settings.userAgentString = effectiveUserAgent
			// Explicit challenge UAs must win over the source header as well as WebView settings.
			if (explicitUserAgent != null) {
				val headers = sourceHeaders.toMutableMap()
				val existingKey = headers.keys.firstOrNull { it.equals("user-agent", ignoreCase = true) }
				if (existingKey != null) {
					headers[existingKey] = explicitUserAgent
				} else {
					headers["User-Agent"] = explicitUserAgent
				}
				sourceHeaders = headers
			}
		}

		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		viewBinding.webView.webViewClient = BrowserClient(this, adBlock, sourceHeaders)
		lifecycleScope.launch {
			try {
				proxyProvider.applyWebViewConfig()
			} catch (e: Exception) {
				e.printStackTraceDebug()
				Snackbar.make(viewBinding.webView, e.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
			}
			if (savedInstanceState == null) {
				val url = intent?.dataString
				if (url.isNullOrEmpty()) {
					finishAfterTransition()
				} else {
					onTitleChanged(
						intent?.getStringExtra(AppRouter.KEY_TITLE) ?: getString(R.string.loading_),
						url,
					)
					if (sourceHeaders.isEmpty()) {
						viewBinding.webView.loadUrl(url)
					} else {
						viewBinding.webView.loadUrl(url, sourceHeaders)
					}
				}
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		super.onCreateOptionsMenu(menu)
		menuInflater.inflate(R.menu.opt_browser, menu)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			viewBinding.webView.stopLoading()
			finishAfterTransition()
			true
		}

		R.id.action_browser -> {
			if (!router.openExternalBrowser(viewBinding.webView.url.orEmpty(), item.title)) {
				Snackbar.make(viewBinding.webView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
			}
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun onPause() {
		// AndroidCookieJar used by Mihon extensions reads from this same CookieManager. Persist the
		// login before the detail/reader screen resumes so the very next image request sees it.
		CookieManager.getInstance().flush()
		super.onPause()
	}

	override fun finish() {
		CookieManager.getInstance().flush()
		if (successCookieUrl != null && successCookieName != null) {
			val currentValue = getCookieValue(successCookieUrl!!, successCookieName!!)
			// Don't require the value to *change* — the user may have renewed a cookie that
			// already existed but was invalid, producing the same token. Just check it exists.
			setResult(if (!currentValue.isNullOrBlank()) RESULT_OK else RESULT_CANCELED)
		} else {
			setResult(RESULT_OK)
		}
		super.finish()
	}

	private fun getSourceHeaders(source: MangaSource): Map<String, String> {
		val httpSource = (source as? MihonMangaSource)?.catalogueSource as? HttpSource
			?: return emptyMap()
		return runCatching {
			httpSource.headers
				.toMultimap()
				.mapValues { (_, values) -> values.firstOrNull().orEmpty() }
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrDefault(emptyMap())
	}

	private fun getCookieValue(url: String, cookieName: String): String? {
		val cookies = CookieManager.getInstance().getCookie(url) ?: return null
		return cookies.split(";")
			.map { it.trim() }
			.firstOrNull { it.startsWith("$cookieName=") }
			?.substringAfter("=")
	}

	class Contract : ActivityResultContract<InteractiveActionRequiredException, Boolean>() {
		override fun createIntent(
			context: Context,
			input: InteractiveActionRequiredException
		): Intent = AppRouter.browserIntent(
			context = context,
			url = input.url,
			source = input.source,
			title = null,
		).apply {
			putExtra(AppRouter.KEY_SUCCESS_COOKIE_URL, input.successCookieUrl)
			putExtra(AppRouter.KEY_SUCCESS_COOKIE_NAME, input.successCookieName)
			if (input.userAgent != null) {
				putExtra(AppRouter.KEY_USER_AGENT, input.userAgent)
			}
		}

		override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
			return resultCode == android.app.Activity.RESULT_OK
		}
	}

	companion object {

		const val TAG = "BrowserActivity"
	}
}
