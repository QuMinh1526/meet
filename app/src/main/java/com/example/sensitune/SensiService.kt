package com.example.sensitune

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import org.json.JSONObject

/**
 * Lightweight foreground service (type specialUse):
 *  - sends heartbeat() every second while the engine is enabled,
 *  - runs the foreground-app watcher for "chỉ trong app đã chọn",
 *  - shows a notification with a "Nhả cảm ứng" action.
 *
 * The service never enables the engine on its own: it requires Prefs.enabled==true.
 */
class SensiService : Service(), ShizukuHelper.Listener {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var watcher: ForegroundWatcher? = null
    private var engineOn = false
    private var heartbeatRunning = false
    private var currentTarget: String? = null
    private var lastNotificationText = ""

    // Overlays + HUD sampling. Both are strictly independent from the engine:
    // failures here only hide a view, they never touch grab/heartbeat state.
    @Volatile
    private var hud: PerfHud? = null

    @Volatile
    private var clock: ClockHud? = null
    private var menu: FloatMenu? = null
    private var perfThread: HandlerThread? = null

    @Volatile
    private var perfHandler: Handler? = null

    @Volatile
    private var perfRunning = false

    @Volatile
    private var perfInFlight = false
    private var lastPerfCsv = ""
    private var lastPerfPkg: String? = null

    // Binder I/O thread: rotation, suspend and coalesced slider values must never
    // run on the main thread.
    private var ioThread: HandlerThread? = null

    @Volatile
    private var ioHandler: Handler? = null

    private val factorLock = Any()
    private var pendingFactorX = 1.0f
    private var pendingFactorY = 1.0f
    private var factorPending = false

    @Volatile
    private var panelOpen = false

    private val factorSendRunnable = Runnable {
        val x: Float
        val y: Float
        synchronized(factorLock) {
            if (!factorPending) return@Runnable
            factorPending = false
            x = pendingFactorX
            y = pendingFactorY
        }
        val ok = ShizukuHelper.setFactor(x, y)
        if (!ok) handler.post { menu?.notifyFactorSendFailed() }
    }

    private val suspendRenewRunnable = object : Runnable {
        override fun run() {
            if (!panelOpen) return
            if (!ShizukuHelper.setScalingSuspended(true)) {
                handler.post { menu?.notifyFactorSendFailed() }
            }
            ioHandler?.postDelayed(this, SUSPEND_RENEW_MS)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val rotation = currentRotation()
            LogBuffer.info(TAG, "display rotation changed -> $rotation")
            postRotation()
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!heartbeatRunning) return
            try {
                if (!prefs.enabled) {
                    LogBuffer.warn(TAG, "prefs.enabled=false -> releasing engine")
                    releaseAll("enabled flag turned off")
                    // Keep the service alive only to host the user's overlays.
                    if (!overlaysWanted()) stopSelf()
                    return
                }
                ShizukuHelper.heartbeat()
            } catch (t: Throwable) {
                LogBuffer.error(TAG, "heartbeat failed", t)
            } finally {
                if (heartbeatRunning) handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        LogBuffer.info(TAG, "service onCreate")
        ShizukuHelper.init(this)
        ShizukuHelper.addListener(this)
        createChannel()
        try {
            perfThread = HandlerThread("perf-sampler").also { it.start() }
            perfHandler = Handler(perfThread!!.looper)
            LogBuffer.info(TAG, "perf sampler thread started")
        } catch (t: Throwable) {
            perfThread = null
            perfHandler = null
            LogBuffer.error(TAG, "perf sampler thread failed", t)
        }

        ensureIo()
        try {
            val dm = getSystemService(DisplayManager::class.java)
            dm?.registerDisplayListener(displayListener, handler)
            LogBuffer.info(TAG, "display listener registered, rotation=${currentRotation()}")
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "registerDisplayListener failed", t)
        }
        // The service can be recreated while the engine is enabled; the new
        // shell process needs the current rotation before the first touch.
        postRotation()
    }

    // ------------------------------------------------------------------
    // Binder I/O thread + rotation
    // ------------------------------------------------------------------

    private fun ensureIo(): Handler? {
        val existing = ioHandler
        if (existing != null && ioThread?.isAlive == true) return existing
        return try {
            val t = HandlerThread("binder-io").also { it.start() }
            ioThread = t
            val h = Handler(t.looper)
            ioHandler = h
            LogBuffer.info(TAG, "binder I/O thread started")
            h
        } catch (t: Throwable) {
            ioThread = null
            ioHandler = null
            LogBuffer.error(TAG, "binder I/O thread failed", t)
            null
        }
    }

    private fun postIo(tag: String, block: () -> Unit) {
        val h = ensureIo() ?: return
        h.post {
            try {
                block()
            } catch (t: Throwable) {
                LogBuffer.error(TAG, "$tag failed", t)
            }
        }
    }

    private fun currentRotation(): Int = try {
        val dm = getSystemService(DisplayManager::class.java)
        dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    } catch (t: Throwable) {
        Surface.ROTATION_0
    }

    private fun postRotation() {
        val rotation = currentRotation()
        postIo("setRotation($rotation)") { ShizukuHelper.setRotation(rotation) }
    }

    /** Rotation first, then all engine settings, then (optionally) enable. */
    private fun pushEngineConfig(reason: String, enableAfter: Boolean, fx: Float, fy: Float) {
        val rotation = currentRotation()
        postIo("engine config ($reason)") {
            ShizukuHelper.setRotation(rotation)
            ShizukuHelper.setEdgeMargin(prefs.edgeMarginPct)
            ShizukuHelper.setSoftRecenter(prefs.softRecenter)
            ShizukuHelper.setReanchor(prefs.reanchor)
            ShizukuHelper.setFactor(fx, fy)
            if (enableAfter) ShizukuHelper.setEnabled(true)
        }
    }

    /** Latest value wins; ~50 ms debounce; the final value is sent on finger up. */
    private fun queueFactor(x: Float, y: Float, immediate: Boolean) {
        synchronized(factorLock) {
            pendingFactorX = x
            pendingFactorY = y
            factorPending = true
        }
        val h = ensureIo() ?: return
        h.removeCallbacks(factorSendRunnable)
        if (immediate) h.post(factorSendRunnable) else h.postDelayed(factorSendRunnable, FACTOR_DEBOUNCE_MS)
    }

    private fun setPanelOpen(open: Boolean) {
        panelOpen = open
        val h = ensureIo() ?: return
        h.removeCallbacks(suspendRenewRunnable)
        if (open) {
            LogBuffer.info(TAG, "floating panel open -> scaling suspended (grab stays active)")
            h.post(suspendRenewRunnable)
        } else {
            LogBuffer.info(TAG, "floating panel closed -> scaling resumed")
            h.post { ShizukuHelper.setScalingSuspended(false) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogBuffer.info(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            Actions.RELEASE -> {
                releaseAll("notification action")
                stopSelf()
                return START_NOT_STICKY
            }
            Actions.STOP_SERVICE -> {
                releaseAll("stop service action")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> Unit
        }

        startForegroundCompat()
        ShizukuHelper.ensureBound()
        syncOverlays()

        if (prefs.onlyInSelectedApps) {
            startWatcher()
        } else if (prefs.enabled) {
            // Plain background mode: engine is already active (or will be once bound).
            applyGlobalFactorAndEnable("background mode")
        }
        if (prefs.enabled || prefs.onlyInSelectedApps) startHeartbeat()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(getString(R.string.notif_running_text))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val releaseIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, ReleaseReceiver::class.java).setAction(Actions.RELEASE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this,
                        R.drawable.ic_notification
                    ),
                    getString(R.string.release_now),
                    releaseIntent
                ).build()
            )
            .build()
    }

    private fun updateNotification(text: String) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "updateNotification failed", t)
        }
    }

    private fun startHeartbeat() {
        if (heartbeatRunning) return
        heartbeatRunning = true
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        LogBuffer.info(TAG, "heartbeat loop started")
    }

    private fun stopHeartbeat() {
        heartbeatRunning = false
        handler.removeCallbacks(heartbeatRunnable)
        LogBuffer.info(TAG, "heartbeat loop stopped")
    }

    private fun startWatcher() {
        if (watcher != null) return
        val w = ForegroundWatcher(this) { pkg -> onStableForeground(pkg) }
        if (!w.hasUsageAccess()) {
            LogBuffer.error(TAG, "usage access missing - cannot run per-app mode")
            updateNotification(getString(R.string.notif_usage_missing))
            return
        }
        watcher = w
        w.start()
        updateNotification(getString(R.string.notif_waiting_app))
    }

    private fun onStableForeground(pkg: String?) {
        try {
            currentTarget = pkg
            currentForeground = pkg
            if (!prefs.enabled) {
                LogBuffer.info(TAG, "foreground=$pkg ignored (master switch off)")
                return
            }
            val targets = prefs.selectedPackages()
            val isTarget = pkg != null && targets.contains(pkg)
            LogBuffer.info(
                TAG,
                "foreground=$pkg isTarget=$isTarget engineOn=$engineOn"
            )
            if (isTarget) {
                val override = prefs.perAppFactor(pkg!!)
                val fx = override?.first ?: prefs.factorX
                val fy = override?.second ?: prefs.factorY
                LogBuffer.info(
                    TAG,
                    "entering target $pkg: applying X=$fx Y=$fy " +
                        "(override=${override != null})"
                )
                pushEngineConfig("target $pkg", true, fx, fy)
                engineOn = true
                hud?.setEngineActive(true)
                updateNotification(getString(R.string.notif_active_in, pkg))
            } else {
                if (engineOn) {
                    LogBuffer.info(TAG, "leaving target app -> releasing grab to IDLE")
                    postIo("disable on leaving target") { ShizukuHelper.setEnabled(false) }
                    engineOn = false
                    hud?.setEngineActive(false)
                }
                updateNotification(getString(R.string.notif_waiting_app))
            }
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "onStableForeground failed", t)
        }
    }

    private fun applyGlobalFactorAndEnable(reason: String) {
        try {
            LogBuffer.info(TAG, "enabling engine: $reason (X=${prefs.factorX}, Y=${prefs.factorY})")
            pushEngineConfig(reason, true, prefs.factorX, prefs.factorY)
            engineOn = true
            hud?.setEngineActive(true)
            updateNotification(getString(R.string.notif_running_text))
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "enable failed", t)
        }
    }

    private fun releaseAll(reason: String) {
        LogBuffer.warn(TAG, "releaseAll: $reason")
        try {
            stopHeartbeat()
            watcher?.stop()
            watcher = null
            panelOpen = false
            ioHandler?.removeCallbacks(suspendRenewRunnable)
            postIo("clear scaling suspension") { ShizukuHelper.setScalingSuspended(false) }
            ShizukuHelper.emergencyRelease(reason)
            engineOn = false
            hud?.setEngineActive(false)
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "releaseAll failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Overlays (floating menu + HUD)
    // ------------------------------------------------------------------

    private fun overlaysWanted(): Boolean =
        prefs.hudEnabled || prefs.clockHudEnabled || prefs.floatMenuEnabled

    private val menuCallbacks = object : FloatMenu.Callbacks {
        override fun onFactorPreview(x: Float, y: Float) {
            queueFactor(x, y, immediate = false)
        }

        override fun onFactorCommit(x: Float, y: Float) {
            queueFactor(x, y, immediate = true)
        }

        override fun onSensitivitySettingsChanged() {
            val margin = prefs.edgeMarginPct
            val soft = prefs.softRecenter
            val reanchor = prefs.reanchor
            postIo("sensitivity settings") {
                ShizukuHelper.setEdgeMargin(margin)
                ShizukuHelper.setSoftRecenter(soft)
                ShizukuHelper.setReanchor(reanchor)
            }
        }

        override fun onPanelOpenChanged(open: Boolean) {
            setPanelOpen(open)
        }

        override fun onEmergencyRelease() {
            releaseAll("floating menu emergency release")
            updateNotification(getString(R.string.notif_released))
        }

        override fun onHudPrefsChanged() {
            syncOverlays()
        }

        override fun onHudLockChanged() {
            try {
                hud?.setLocked(prefs.hudLocked)
                clock?.setLocked(prefs.hudLocked)
            } catch (t: Throwable) {
                LogBuffer.error(TAG, "hud lock change failed", t)
            }
        }

        override fun onHudMove(dx: Int, dy: Int) {
            try {
                hud?.moveBy(dx, dy)
            } catch (t: Throwable) {
                LogBuffer.error(TAG, "hud move failed", t)
            }
        }

        override fun engineActive(): Boolean = engineOn

        override fun factorSendFailed() {
            // The panel already shows its red dot; nothing else to do here.
        }
    }

    /** Brings overlay windows in sync with Prefs; safe to call repeatedly. */
    private fun syncOverlays() {
        try {
            if (!overlaysWanted()) {
                destroyOverlays()
                if (!prefs.enabled && !prefs.onlyInSelectedApps) stopSelf()
                return
            }
            if (!OverlayUi.canDraw(this)) {
                LogBuffer.warn(TAG, "overlay permission missing - menu/HUD stays hidden")
                destroyOverlays()
                updateNotification(getString(R.string.overlay_permission_missing_short))
                return
            }

            if (prefs.floatMenuEnabled) {
                if (menu == null) {
                    menu = FloatMenu(applicationContext, prefs, menuCallbacks)
                }
                menu?.show()
            } else {
                menu?.hide()
                menu = null
            }

            if (prefs.hudEnabled) {
                if (hud == null) hud = PerfHud(applicationContext, prefs)
                hud?.setEngineActive(engineOn)
                hud?.show()
                startPerfLoop()
            } else {
                stopPerfLoop()
                hud?.hide()
                hud = null
            }

            if (prefs.clockHudEnabled) {
                if (clock == null) clock = ClockHud(applicationContext, prefs)
                clock?.show()
            } else {
                clock?.hide()
                clock = null
            }
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "syncOverlays failed", t)
        }
    }

    private fun destroyOverlays() {
        stopPerfLoop()
        try {
            hud?.hide()
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "hud hide failed", t)
        }
        hud = null
        try {
            clock?.hide()
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "clock hide failed", t)
        }
        clock = null
        try {
            menu?.hide()
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "menu hide failed", t)
        }
        menu = null
        lastPerfCsv = ""
        lastPerfPkg = null
    }

    private fun ensurePerfThread(): Handler? {
        val existing = perfHandler
        if (existing != null && perfThread?.isAlive == true) return existing
        return try {
            val t = HandlerThread("perf-sampler")
            t.start()
            perfThread = t
            val h = Handler(t.looper)
            perfHandler = h
            LogBuffer.info(TAG, "perf sampler thread created")
            h
        } catch (t: Throwable) {
            perfThread = null
            perfHandler = null
            LogBuffer.error(TAG, "perf sampler thread failed", t)
            null
        }
    }

    private fun startPerfLoop() {
        if (perfRunning) return
        val h = ensurePerfThread() ?: return
        perfRunning = true
        h.post(perfTick)
        LogBuffer.info(TAG, "perf sampler loop started")
    }

    private fun stopPerfLoop() {
        if (!perfRunning) return
        perfRunning = false
        perfHandler?.removeCallbacks(perfTick)
        LogBuffer.info(TAG, "perf sampler loop stopped")
    }

    private val perfTick = object : Runnable {
        override fun run() {
            if (!perfRunning) return
            try {
                samplePerf()
            } catch (t: Throwable) {
                LogBuffer.error(TAG, "perf sample failed", t)
            } finally {
                if (perfRunning) perfHandler?.postDelayed(this, PERF_INTERVAL_MS)
            }
        }
    }

    private fun samplePerf() {
        if (perfInFlight) {
            LogBuffer.debug(TAG, "perf sample skipped: previous request still running")
            return
        }
        if (!prefs.hudEnabled) return
        if (hud == null) return
        if (!OverlayUi.canDraw(this)) {
            LogBuffer.warn(TAG, "overlay permission revoked - removing overlays")
            stopPerfLoop()
            handler.post { destroyOverlays() }
            return
        }
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return
        if (ShizukuHelper.service == null) return
        val csv = prefs.hudEnabledCsv()
        if (csv.isEmpty()) return

        perfInFlight = true
        try {
            val pkg = resolveForegroundPkg()
            if (csv != lastPerfCsv || pkg != lastPerfPkg) {
                ShizukuHelper.configurePerf(csv, pkg)
                lastPerfCsv = csv
                lastPerfPkg = pkg
            }
            val start = SystemClock.elapsedRealtime()
            val json = ShizukuHelper.perfSnapshot()
            val took = SystemClock.elapsedRealtime() - start
            if (json == null) {
                LogBuffer.debug(TAG, "perf snapshot unavailable (UserService not bound)")
                return
            }
            LogBuffer.debug(TAG, "perf sample ${took}ms metrics=$csv pkg=${pkg ?: "auto"}")
            val obj = JSONObject(json)
            handler.post { hud?.update(obj) }
        } finally {
            perfInFlight = false
        }
    }

    private fun resolveForegroundPkg(): String? {
        currentForeground?.let { return it }
        return try {
            val usm = getSystemService(UsageStatsManager::class.java) ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 10_000, now) ?: return null
            var pkg: String? = null
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    pkg = e.packageName
                }
            }
            pkg
        } catch (t: Throwable) {
            null
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            hud?.onConfigurationChanged()
            clock?.onConfigurationChanged()
            menu?.onConfigurationChanged()
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "overlay rotation handling failed", t)
        }
    }

    override fun onDestroy() {
        LogBuffer.warn(TAG, "service onDestroy - releasing for safety")
        try {
            releaseAll("service onDestroy")
            destroyOverlays()
            try {
                getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
            } catch (t: Throwable) {
                LogBuffer.error(TAG, "unregisterDisplayListener failed", t)
            }
            ioHandler?.removeCallbacksAndMessages(null)
            ioThread?.quitSafely()
            ioThread = null
            ioHandler = null
            perfThread?.quitSafely()
            perfThread = null
            perfHandler = null
            prefs.enabled = false
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "onDestroy cleanup failed", t)
        } finally {
            ShizukuHelper.removeListener(this)
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            LogBuffer.info(TAG, "service destroyed")
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        LogBuffer.info(TAG, "task removed, runBackground=${prefs.runBackground}")
        // Never leave stale overlay windows or a running sampler behind.
        destroyOverlays()
        perfThread?.quitSafely()
        perfThread = null
        perfHandler = null
        if (!prefs.runBackground) {
            releaseAll("task removed without background mode")
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onShizukuStateChanged(state: ShizukuHelper.State) {
        LogBuffer.info(TAG, "Shizuku state changed: $state")
        if (state == ShizukuHelper.State.READY) {
            ShizukuHelper.ensureBound()
        } else if (state == ShizukuHelper.State.NOT_RUNNING ||
            state == ShizukuHelper.State.NOT_INSTALLED
        ) {
            releaseAll("Shizuku unavailable")
            updateNotification(getString(R.string.notif_shizuku_lost))
        }
    }

    override fun onServiceChanged(bound: Boolean) {
        LogBuffer.info(TAG, "UserService bound=$bound")
        // A new shell process has a fresh config; rotation must be re-sent first.
        lastPerfCsv = ""
        lastPerfPkg = null
        if (bound) postRotation()
        if (bound && prefs.enabled && !prefs.onlyInSelectedApps) {
            applyGlobalFactorAndEnable("service rebound")
        }
        if (bound && prefs.onlyInSelectedApps && currentTarget != null) {
            onStableForeground(currentTarget)
        }
    }

    companion object {
        private const val TAG = "SensiService"
        const val CHANNEL_ID = "sensitune_service"
        const val NOTIF_ID = 1001
        private const val HEARTBEAT_INTERVAL_MS = 1000L
        private const val PERF_INTERVAL_MS = 1000L
        private const val FACTOR_DEBOUNCE_MS = 50L
        private const val SUSPEND_RENEW_MS = 2000L

        /** Foreground package seen by the watcher, exposed for the debug screen. */
        @Volatile
        var currentForeground: String? = null
            private set
    }
}
