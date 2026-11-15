package com.example.sensitune

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.inputmethod.InputMethodManager

/**
 * Polls UsageStatsManager every 500 ms (only while "chỉ trong app đã chọn" is on),
 * debounces foreground changes for 300 ms, and ignores SystemUI / launcher / IME.
 */
class ForegroundWatcher(
    context: Context,
    private val onStableForeground: (String?) -> Unit
) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val usageManager =
        appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val ignored = HashSet<String>()

    private var running = false
    private var candidate: String? = null
    private var candidateSince = 0L
    private var lastEventTs = 0L
    private var emitted: String? = null
    private var reportedInitial = false

    @Volatile
    var currentForeground: String? = null
        private set

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                pollOnce()
            } catch (t: Throwable) {
                LogBuffer.error(TAG, "poll failed", t)
            } finally {
                if (running) handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    fun hasUsageAccess(): Boolean {
        return try {
            val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "check usage access failed", t)
            false
        }
    }

    fun start() {
        if (running) return
        if (!hasUsageAccess()) {
            LogBuffer.error(TAG, "PACKAGE_USAGE_STATS chưa được cấp - không thể theo dõi app")
            return
        }
        loadIgnoredPackages()
        running = true
        candidate = null
        emitted = null
        reportedInitial = false
        candidateSince = System.currentTimeMillis()
        LogBuffer.info(TAG, "watcher started (poll ${POLL_INTERVAL_MS}ms, debounce ${DEBOUNCE_MS}ms)")
        handler.post(pollRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(pollRunnable)
        LogBuffer.info(TAG, "watcher stopped")
    }

    private fun loadIgnoredPackages() {
        ignored.clear()
        ignored.add("com.android.systemui")
        ignored.add(appContext.packageName)
        try {
            val pm = appContext.packageManager
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val homeApps = pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            homeApps.forEach { ignored.add(it.activityInfo.packageName) }
            val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.forEach { ignored.add(it.packageName) }
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "loadIgnoredPackages failed", t)
        }
        LogBuffer.info(TAG, "ignored packages: ${ignored.joinToString(", ")}")
    }

    private fun pollOnce() {
        val now = System.currentTimeMillis()
        val events = usageManager.queryEvents(now - WINDOW_MS, now)
        if (events == null) {
            LogBuffer.warn(TAG, "queryEvents returned null")
            return
        }
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            if (type != UsageEvents.Event.MOVE_TO_FOREGROUND &&
                type != UsageEvents.Event.MOVE_TO_BACKGROUND
            ) {
                continue
            }
            val pkg = event.packageName ?: continue
            val ts = event.timeStamp
            if (ts < lastEventTs && lastEventTs - ts < 10_000) {
                // stale event from an earlier poll
                continue
            }
            lastEventTs = maxOf(lastEventTs, ts)

            if (ignored.contains(pkg)) continue

            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                currentForeground = pkg
                if (pkg != candidate) {
                    candidate = pkg
                    candidateSince = ts
                    LogBuffer.debug(TAG, "candidate foreground: $pkg")
                }
            } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                if (pkg == emitted) {
                    LogBuffer.debug(TAG, "target $pkg went to background")
                    candidate = null
                    candidateSince = ts
                    currentForeground = null
                }
            }
        }

        val nowStable = System.currentTimeMillis()
        if (!reportedInitial) {
            reportedInitial = true
            emitted = candidate
            onStableForeground(emitted)
            LogBuffer.info(TAG, "initial foreground: ${emitted ?: "none"}")
            return
        }
        if (nowStable - candidateSince >= DEBOUNCE_MS && candidate != emitted) {
            val latency = nowStable - candidateSince
            emitted = candidate
            LogBuffer.info(
                TAG,
                "foreground changed -> ${candidate ?: "none"} (detect latency ${latency}ms)"
            )
            onStableForeground(emitted)
        }
    }

    private companion object {
        const val TAG = "ForegroundWatcher"
        const val POLL_INTERVAL_MS = 500L
        const val DEBOUNCE_MS = 300L
        const val WINDOW_MS = 3000L
    }
}
