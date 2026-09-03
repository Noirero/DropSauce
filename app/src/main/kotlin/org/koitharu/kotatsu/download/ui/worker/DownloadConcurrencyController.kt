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
 * Process-wide worker gate for manga/novel downloads. The configured "parallel sources" value is
 * treated as the number of download jobs that may run concurrently, regardless of whether two jobs
 * happen to come from the same Mihon source. Page concurrency is still limited independently by
 * [DownloadPerformanceSettings.parallelPageLimit] inside every worker.
 */
@Singleton
class DownloadConcurrencyController @Inject constructor() {
	private val mutex = Mutex()
	private var activeDownloads = 0
	private val revision = MutableStateFlow(0L)

	suspend fun <T> withPermit(
		limit: Int,
		block: suspend () -> T,
	): T {
		acquire(limit.coerceAtLeast(1))
		return try {
			block()
		} finally {
			// A cancelled worker must still release its slot. Without NonCancellable a cancellation
			// arriving at mutex acquisition can strand the permit until process restart.
			withContext(NonCancellable) {
				release()
			}
		}
	}

	private suspend fun acquire(limit: Int) {
		while (true) {
			val observedRevision = revision.value
			val acquired = mutex.withLock {
				if (activeDownloads < limit) {
					activeDownloads++
					true
				} else {
					false
				}
			}
			if (acquired) return
			// StateFlow prevents a lost wake-up if a slot is released between the check above and
			// this suspension: a changed revision is observed immediately.
			revision.first { it != observedRevision }
		}
	}

	private suspend fun release() {
		mutex.withLock {
			if (activeDownloads > 0) {
				activeDownloads--
				revision.value = revision.value + 1L
			}
		}
	}
}
