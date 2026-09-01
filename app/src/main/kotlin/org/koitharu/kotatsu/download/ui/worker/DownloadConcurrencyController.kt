package org.koitharu.kotatsu.download.ui.worker

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide source gate matching Kahon/Mihon's "concurrent sources" model: only one download
 * worker for a given source is active at once, while different sources can run in parallel up to
 * the configured limit. Existing workers are never cancelled when the limit is lowered; the new
 * value simply applies to the next acquisition.
 */
@Singleton
class DownloadConcurrencyController @Inject constructor() {
	private val mutex = Mutex()
	private val activeSources = HashSet<String>()
	private val revision = MutableStateFlow(0L)

	suspend fun <T> withSourcePermit(
		sourceKey: String,
		limit: Int,
		block: suspend () -> T,
	): T {
		acquire(sourceKey, limit.coerceAtLeast(1))
		return try {
			block()
		} finally {
			// A cancelled worker must still release its source slot. Without NonCancellable a
			// cancellation arriving at mutex acquisition can strand the permit until process restart.
			withContext(NonCancellable) {
				release(sourceKey)
			}
		}
	}

	private suspend fun acquire(sourceKey: String, limit: Int) {
		while (true) {
			val observedRevision = revision.value
			val acquired = mutex.withLock {
				if (sourceKey !in activeSources && activeSources.size < limit) {
					activeSources.add(sourceKey)
					true
				} else {
					false
				}
			}
			if (acquired) return
			// StateFlow prevents a lost wake-up if a permit is released between the check above and
			// this suspension: a changed revision is observed immediately.
			revision.first { it != observedRevision }
		}
	}

	private suspend fun release(sourceKey: String) {
		mutex.withLock {
			if (activeSources.remove(sourceKey)) {
				revision.value = revision.value + 1L
			}
		}
	}
}
