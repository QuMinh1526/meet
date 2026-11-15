package com.example.sensitune

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

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

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!heartbeatRunning) return
            try {
                if (!prefs.enabled) {
                    LogBuffer.warn(TAG, "prefs.enabled=false -> releasing and stopping service")
                    releaseAll("enabled flag turned off")
                    stopSelf()
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

        if (prefs.onlyInSelectedApps) {
            startWatcher()
        } else if (prefs.enabled) {
            // Plain background mode: engine is already active (or will be once bound).
            applyGlobalFactorAndEnable("background mode")
        }
        startHeartbeat()
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
                ShizukuHelper.setFactor(fx, fy)
                ShizukuHelper.setEnabled(true)
                engineOn = true
                updateNotification(getString(R.string.notif_active_in, pkg))
            } else {
                if (engineOn) {
                    LogBuffer.info(TAG, "leaving target app -> releasing grab to IDLE")
                    ShizukuHelper.setEnabled(false)
                    engineOn = false
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
            ShizukuHelper.setFactor(prefs.factorX, prefs.factorY)
            ShizukuHelper.setEnabled(true)
            engineOn = true
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
            ShizukuHelper.emergencyRelease(reason)
            engineOn = false
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "releaseAll failed", t)
        }
    }

    override fun onDestroy() {
        LogBuffer.warn(TAG, "service onDestroy - releasing for safety")
        try {
            releaseAll("service onDestroy")
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

        /** Foreground package seen by the watcher, exposed for the debug screen. */
        @Volatile
        var currentForeground: String? = null
            private set
    }
}
