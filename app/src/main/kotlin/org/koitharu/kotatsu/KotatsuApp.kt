package org.koitharu.kotatsu

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.acra.ACRA
import org.koitharu.kotatsu.core.BaseApp
import org.koitharu.kotatsu.core.logs.CrashLogStore
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.main.ui.MainActivity

class KotatsuApp : BaseApp() {

	@Volatile
	private var recoveredCrashDialogShown = false

	override fun onCreate() {
		super.onCreate()
		if (ACRA.isACRASenderServiceProcess()) return

		// Reading ApplicationExitInfo (and especially an ANR trace) can touch disk and return a large
		// payload. Never do it on the main thread: diagnostics must not make startup jank or create a
		// new ANR while trying to explain the previous one.
		processLifecycleScope.launch(Dispatchers.IO) {
			CrashLogStore.capturePreviousSystemExit(this@KotatsuApp)
		}
		registerActivityLifecycleCallbacks(RecoveredCrashDialogCallbacks())
	}

	private inner class RecoveredCrashDialogCallbacks : Application.ActivityLifecycleCallbacks {
		override fun onActivityResumed(activity: Activity) {
			if (recoveredCrashDialogShown || activity !is MainActivity) return
			processLifecycleScope.launch(Dispatchers.IO) {
				val log = CrashLogStore.pendingLog(activity) ?: return@launch
				activity.runOnUiThread {
					if (recoveredCrashDialogShown || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
					recoveredCrashDialogShown = true
					val preview = log.take(MAX_DIALOG_LOG_CHARS).let {
						if (log.length > it.length) "$it\n\n… Full log is available through Copy details." else it
					}
					buildAlertDialog(activity) {
						setTitle(R.string.error_occurred)
						setMessage(preview)
						setCancelable(false)
						setPositiveButton(R.string.copy_details) { _, _ ->
							activity.copyToClipboard(activity.getString(R.string.error_details), log)
							CrashLogStore.clearPending(activity)
						}
						setNegativeButton(R.string.close) { _, _ ->
							CrashLogStore.clearPending(activity)
						}
					}.show()
				}
			}
		}

		override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
		override fun onActivityStarted(activity: Activity) = Unit
		override fun onActivityPaused(activity: Activity) = Unit
		override fun onActivityStopped(activity: Activity) = Unit
		override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
		override fun onActivityDestroyed(activity: Activity) = Unit
	}

	private companion object {
		const val MAX_DIALOG_LOG_CHARS = 12_000
	}
}
