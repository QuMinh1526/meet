package com.example.sensitune

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Display
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.sensitune.ui.kit.LabeledSlider
import com.example.sensitune.ui.kit.SectionCard
import com.example.sensitune.ui.kit.ToggleRow
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Single screen UI. The engine is NEVER enabled automatically: the user must press
 * the switch. Leaving the app releases the engine unless "Chạy nền" or
 * "Chỉ trong app đã chọn" is on (then SensiService owns the heartbeat).
 */
class MainActivity : Activity(), ShizukuHelper.Listener {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvShizuku: TextView
    private lateinit var btnReconnect: Button
    private lateinit var tvService: TextView
    private lateinit var swEnable: Switch
    private lateinit var btnEmergency: Button
    private lateinit var llSensitivity: LinearLayout
    private lateinit var sliderFactorX: LabeledSlider
    private lateinit var sliderFactorY: LabeledSlider
    private lateinit var rowLockXY: ToggleRow
    private lateinit var btnTest: Button
    private lateinit var tvTestResult: TextView
    private lateinit var btnDiagnose: Button
    private lateinit var btnLogs: Button
    private lateinit var btnApps: Button
    private lateinit var swOnlyApps: Switch
    private lateinit var swBackground: Switch
    private lateinit var tvForeground: TextView
    private lateinit var tvEngineStatus: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var tvUsageWarning: TextView
    private lateinit var swFloatMenu: Switch
    private lateinit var swHud: Switch
    private lateinit var swClockHud: Switch
    private lateinit var tvOverlayPermission: TextView

    private var suppressSwitchCallbacks = false
    private var testing = false
    private var testSecondsLeft = 0
    private var enableInProgress = false
    private var nullStatusCount = 0

    // One binder I/O thread for factors/settings/rotation; factor values are
    // coalesced (latest wins, 50 ms debounce) so dragging never floods Binder.
    private var engineIoThread: HandlerThread? = null

    @Volatile
    private var engineIoHandler: Handler? = null
    private val factorLock = Any()
    private var pendingFactorX = 1f
    private var pendingFactorY = 1f
    private var factorPending = false

    private val factorSendRunnable = Runnable {
        val x: Float
        val y: Float
        synchronized(factorLock) {
            if (!factorPending) return@Runnable
            factorPending = false
            x = pendingFactorX
            y = pendingFactorY
        }
        ShizukuHelper.setFactor(x, y)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        bindViews()
        setupFactorSliders()
        setupEdgeSettings()
        setupSwitches()
        setupOverlayControls()
        setupButtons()

        ShizukuHelper.init(this)
        ShizukuHelper.addListener(this)
        LogBuffer.info("MainActivity", "created (version ${BuildConfig.VERSION_NAME})")

        if (prefs.testDoneThisSession.not()) {
            tvTestResult.text = getString(R.string.test_hint_first)
        }
        updateModeUi()
        refreshShizukuUi()
    }

    private fun bindViews() {
        tvShizuku = findViewById(R.id.tvShizuku)
        btnReconnect = findViewById(R.id.btnReconnect)
        tvService = findViewById(R.id.tvService)
        swEnable = findViewById(R.id.swEnable)
        btnEmergency = findViewById(R.id.btnEmergency)
        llSensitivity = findViewById(R.id.llSensitivity)
        btnTest = findViewById(R.id.btnTest)
        tvTestResult = findViewById(R.id.tvTestResult)
        btnDiagnose = findViewById(R.id.btnDiagnose)
        btnLogs = findViewById(R.id.btnLogs)
        btnApps = findViewById(R.id.btnApps)
        swOnlyApps = findViewById(R.id.swOnlyApps)
        swBackground = findViewById(R.id.swBackground)
        tvForeground = findViewById(R.id.tvForeground)
        tvEngineStatus = findViewById(R.id.tvEngineStatus)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        tvUsageWarning = findViewById(R.id.tvUsageWarning)
        swFloatMenu = findViewById(R.id.swFloatMenu)
        swHud = findViewById(R.id.swHud)
        swClockHud = findViewById(R.id.swClockHud)
        tvOverlayPermission = findViewById(R.id.tvOverlayPermission)
    }

    // ------------------------------------------------------------------
    // Floating menu + HUD switches (overlay permission)
    // ------------------------------------------------------------------

    private fun setupOverlayControls() {
        suppressSwitchCallbacks = true
        swFloatMenu.isChecked = prefs.floatMenuEnabled
        swHud.isChecked = prefs.hudEnabled
        swClockHud.isChecked = prefs.clockHudEnabled
        suppressSwitchCallbacks = false

        swFloatMenu.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (!suppressSwitchCallbacks) setOverlayPref(menu = true, on = checked)
        }
        swHud.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (!suppressSwitchCallbacks) setOverlayPref(menu = false, on = checked)
        }
        swClockHud.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (!suppressSwitchCallbacks) setOverlayPref(menu = false, on = checked, clock = true)
        }
    }

    private fun setOverlayPref(menu: Boolean, on: Boolean, clock: Boolean = false) {
        when {
            clock -> prefs.clockHudEnabled = on
            menu -> prefs.floatMenuEnabled = on
            else -> prefs.hudEnabled = on
        }
        LogBuffer.info(
            "MainActivity",
            "${when { clock -> "clockHud"; menu -> "floatMenu"; else -> "hud" }} -> $on"
        )
        if (on && !OverlayUi.canDraw(this)) {
            toast(getString(R.string.overlay_permission_needed))
            openOverlaySettings()
        }
        if (on) requestNotificationPermissionIfNeeded()
        // The foreground service hosts both overlays; starting it is harmless when
        // the engine is off (it will not enable the engine by itself).
        startSensiService()
        updateOverlayUi()
    }

    private fun openOverlaySettings() {
        try {
            startActivity(OverlayUi.overlayIntent(this))
        } catch (t: Throwable) {
            LogBuffer.error("MainActivity", "cannot open overlay settings", t)
            toast(getString(R.string.overlay_settings_failed))
        }
    }

    private fun updateOverlayUi() {
        suppressSwitchCallbacks = true
        swFloatMenu.isChecked = prefs.floatMenuEnabled
        swHud.isChecked = prefs.hudEnabled
        swClockHud.isChecked = prefs.clockHudEnabled
        suppressSwitchCallbacks = false
        val granted = OverlayUi.canDraw(this)
        tvOverlayPermission.text = if (granted) {
            getString(R.string.overlay_permission_ok)
        } else {
            getString(R.string.overlay_permission_missing)
        }
        tvOverlayPermission.setTextColor(
            if (granted) 0xFF2E7D32.toInt() else 0xFFD32F2F.toInt()
        )
    }

    // ------------------------------------------------------------------
    // Sensitivity + edge settings (shared ui.kit widgets)
    // ------------------------------------------------------------------

    private fun setupFactorSliders() {
        val card = SectionCard(this, getString(R.string.sensitivity_section))
        sliderFactorX = LabeledSlider(
            this, getString(R.string.factor_x_horizontal), 0.50f, 10f, 0.05f, prefs.factorX
        )
        sliderFactorY = LabeledSlider(
            this, getString(R.string.factor_y_vertical), 0.50f, 10f, 0.05f, prefs.factorY
        )

        sliderFactorX.onChange = { value, fromUser ->
            if (fromUser) {
                prefs.factorX = value
                if (prefs.lockXY) {
                    prefs.factorY = value
                    sliderFactorY.setValue(value, notify = false)
                }
                applyFactorLive(prefs.factorX, prefs.factorY)
            }
        }
        sliderFactorX.onStopTracking = { applyFactorLive(prefs.factorX, prefs.factorY) }
        sliderFactorY.onChange = { value, fromUser ->
            if (fromUser) {
                prefs.factorY = value
                if (prefs.lockXY) {
                    prefs.factorX = value
                    sliderFactorX.setValue(value, notify = false)
                }
                applyFactorLive(prefs.factorX, prefs.factorY)
            }
        }
        sliderFactorY.onStopTracking = { applyFactorLive(prefs.factorX, prefs.factorY) }

        card.addRow(sliderFactorX)
        card.addRow(sliderFactorY)

        rowLockXY = ToggleRow(this, getString(R.string.lock_xy), prefs.lockXY)
        rowLockXY.onCheckedChange = { checked ->
            prefs.lockXY = checked
            if (checked) {
                prefs.factorY = prefs.factorX
                sliderFactorY.setValue(prefs.factorX, notify = false)
                applyFactorLive(prefs.factorX, prefs.factorX)
            }
            LogBuffer.info("MainActivity", "lock X=Y -> $checked")
        }
        card.addRow(rowLockXY)
        llSensitivity.addView(card)
    }

    /** Edge buffer / soft recenter / reanchor — all engine-side swipe fixes. */
    private fun setupEdgeSettings() {
        val card = SectionCard(this, getString(R.string.swipe_section))
        val margin = LabeledSlider(
            this, getString(R.string.edge_margin_label), 0f, 25f, 1f, prefs.edgeMarginPct.toFloat()
        )
        margin.onChange = { value, fromUser ->
            if (fromUser) {
                prefs.edgeMarginPct = value.roundToInt()
                pushSensitivitySettings()
            }
        }
        margin.onStopTracking = { pushSensitivitySettings() }
        card.addRow(margin)

        val soft = ToggleRow(this, getString(R.string.soft_recenter_label), prefs.softRecenter)
        soft.onCheckedChange = { checked ->
            prefs.softRecenter = checked
            pushSensitivitySettings()
        }
        card.addRow(soft)

        val reanchorWarning = TextView(this).apply {
            text = getString(R.string.reanchor_warning)
            setTextColor(0xFFD32F2F.toInt())
            textSize = 10f
            setPadding(0, 0, 0, 8)
            visibility = if (prefs.reanchor) View.VISIBLE else View.GONE
        }
        val reanchor = ToggleRow(this, getString(R.string.reanchor_label), prefs.reanchor)
        reanchor.onCheckedChange = { checked ->
            prefs.reanchor = checked
            reanchorWarning.visibility = if (checked) View.VISIBLE else View.GONE
            pushSensitivitySettings()
        }
        card.addRow(reanchor)
        card.addRow(reanchorWarning)
        llSensitivity.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() })
    }

    private fun applyFactorLive(x: Float, y: Float) {
        if (ShizukuHelper.service == null) return
        if (!prefs.enabled && !testing) return
        synchronized(factorLock) {
            pendingFactorX = x
            pendingFactorY = y
            factorPending = true
        }
        val h = ensureEngineIo() ?: return
        h.removeCallbacks(factorSendRunnable)
        h.postDelayed(factorSendRunnable, 50L)
    }

    private fun pushSensitivitySettings() {
        if (ShizukuHelper.service == null || (!prefs.enabled && !testing)) return
        val margin = prefs.edgeMarginPct
        val soft = prefs.softRecenter
        val reanchor = prefs.reanchor
        postEngineIo("push sensitivity settings") {
            ShizukuHelper.setEdgeMargin(margin)
            ShizukuHelper.setSoftRecenter(soft)
            ShizukuHelper.setReanchor(reanchor)
        }
    }

    private fun ensureEngineIo(): Handler? {
        val existing = engineIoHandler
        if (existing != null && engineIoThread?.isAlive == true) return existing
        return try {
            val t = HandlerThread("engine-io").also { it.start() }
            engineIoThread = t
            val h = Handler(t.looper)
            engineIoHandler = h
            h
        } catch (t: Throwable) {
            LogBuffer.error("MainActivity", "engine I/O thread failed", t)
            engineIoThread = null
            engineIoHandler = null
            null
        }
    }

    private fun postEngineIo(tag: String, block: () -> Unit) {
        val h = ensureEngineIo() ?: return
        h.post {
            try {
                block()
            } catch (t: Throwable) {
                LogBuffer.error("MainActivity", "$tag failed", t)
            }
        }
    }

    // ------------------------------------------------------------------
    // Switches / buttons
    // ------------------------------------------------------------------

    private fun setupSwitches() {
        swEnable.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
            if (checked) requestEnable() else requestDisable("công tắc tắt")
        }

        swBackground.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
            prefs.runBackground = checked
            LogBuffer.info("MainActivity", "runBackground -> $checked")
            if (checked) requestNotificationPermissionIfNeeded()
            if (prefs.enabled) {
                if (checked || prefs.onlyInSelectedApps) {
                    startSensiService()
                } else {
                    requestDisable("tắt chạy nền")
                }
            }
        }

        swOnlyApps.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
            if (checked && !hasUsageAccess()) {
                LogBuffer.warn("MainActivity", "usage access missing; opening settings")
                toast(getString(R.string.usage_permission_needed))
                openUsageAccessSettings()
                revertOnlyAppsSwitch()
                return@setOnCheckedChangeListener
            }
            prefs.onlyInSelectedApps = checked
            LogBuffer.info("MainActivity", "onlyInSelectedApps -> $checked")
            if (checked && prefs.selectedPackages().isEmpty()) {
                toast(getString(R.string.pick_apps_first))
            }
            if (prefs.enabled) {
                if (checked) {
                    startSensiService()
                } else if (!prefs.runBackground) {
                    requestDisable("tắt chế độ theo app")
                } else {
                    // Background service is still needed for heartbeat; it will
                    // re-apply the global factor on the next enable.
                    startSensiService()
                }
            }
            updateModeUi()
        }
    }

    private fun setupButtons() {
        btnReconnect.setOnClickListener {
            LogBuffer.info("MainActivity", "reconnect pressed")
            ShizukuHelper.refreshState()
            if (ShizukuHelper.state == ShizukuHelper.State.NO_PERMISSION) {
                ShizukuHelper.requestPermission()
                toast(getString(R.string.shizuku_permission_requested))
            } else {
                ShizukuHelper.ensureBound()
                toast(getString(R.string.connecting))
            }
        }

        btnEmergency.setOnClickListener { emergencyRelease("nút đỏ trong app") }

        btnDiagnose.setOnClickListener { runDiagnostics() }
        btnLogs.setOnClickListener {
            startActivity(Intent(this, DebugLogActivity::class.java))
        }
        btnApps.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }

        btnTest.setOnClickListener { startTenSecondTest() }
    }

    private fun revertOnlyAppsSwitch() {
        suppressSwitchCallbacks = true
        swOnlyApps.isChecked = false
        suppressSwitchCallbacks = false
    }

    private fun revertEnableSwitch() {
        suppressSwitchCallbacks = true
        swEnable.isChecked = false
        suppressSwitchCallbacks = false
    }

    // ------------------------------------------------------------------
    // Enable / disable
    // ------------------------------------------------------------------

    private fun requestEnable() {
        LogBuffer.info("MainActivity", "user requested ENABLE")
        if (ShizukuHelper.state == ShizukuHelper.State.NO_PERMISSION) {
            ShizukuHelper.requestPermission()
            toast(getString(R.string.shizuku_permission_requested))
            revertEnableSwitch()
            return
        }
        if (ShizukuHelper.state != ShizukuHelper.State.READY) {
            onEnableFailed(getString(R.string.shizuku_not_ready))
            return
        }
        enableInProgress = true
        if (prefs.onlyInSelectedApps && prefs.selectedPackages().isEmpty()) {
            onEnableFailed(getString(R.string.pick_apps_first))
            return
        }
        ensureServiceThen(
            onReady = {
                val fx = prefs.factorX
                val fy = prefs.factorY
                LogBuffer.info("MainActivity", "enabling engine X=$fx Y=$fy")
                enableEngineAsync(fx, fy)
                prefs.enabled = true
                enableInProgress = false
                if (!prefs.runBackground && !prefs.onlyInSelectedApps) {
                    toast(getString(R.string.will_release_on_leave))
                }
                if (prefs.runBackground || prefs.onlyInSelectedApps) {
                    requestNotificationPermissionIfNeeded()
                    startSensiService()
                } else {
                    startActivityHeartbeat()
                }
                handler.postDelayed({ verifyEnabled() }, 700)
                updateModeUi()
            },
            onFailed = { onEnableFailed(getString(R.string.service_connect_failed)) }
        )
    }

    private fun verifyEnabled(attempt: Int = 0) {
        if (!prefs.enabled) return
        if (prefs.onlyInSelectedApps) {
            tvService.text = getString(R.string.state_waiting_target)
            return
        }
        val status = ShizukuHelper.statusJson()
        if (status != null) {
            try {
                val obj = JSONObject(status)
                val state = obj.optString("state", "?")
                if (state == "ERROR") {
                    val msg = obj.optString("lastError", "")
                    onEnableFailed(
                        getString(
                            R.string.engine_error_detail,
                            obj.optInt("lastErrorCode", -1),
                            msg
                        )
                    )
                    return
                }
                if (state == "ACTIVE") return
            } catch (t: Throwable) {
                LogBuffer.error("MainActivity", "verifyEnabled parse failed", t)
            }
        }
        if (attempt < 5 && prefs.enabled) {
            handler.postDelayed({ verifyEnabled(attempt + 1) }, 500)
        }
    }

    private fun onEnableFailed(message: String) {
        LogBuffer.error("MainActivity", "enable failed: $message")
        enableInProgress = false
        prefs.enabled = false
        revertEnableSwitch()
        stopActivityHeartbeat()
        stopSensiService()
        tvService.text = message
        AlertDialog.Builder(this)
            .setTitle(R.string.enable_failed_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.open_logs) { _, _ ->
                startActivity(Intent(this, DebugLogActivity::class.java))
            }
            .show()
    }

    /**
     * Rotation + engine settings + factors, in order, off the main thread.
     * The shell process drops any config sent after a restart otherwise.
     */
    private fun enableEngineAsync(fx: Float, fy: Float) {
        val rotation = currentRotation()
        val margin = prefs.edgeMarginPct
        val soft = prefs.softRecenter
        val reanchor = prefs.reanchor
        postEngineIo("engine enable") {
            ShizukuHelper.setRotation(rotation)
            ShizukuHelper.setEdgeMargin(margin)
            ShizukuHelper.setSoftRecenter(soft)
            ShizukuHelper.setReanchor(reanchor)
            ShizukuHelper.setFactor(fx, fy)
            ShizukuHelper.setEnabled(true)
            LogBuffer.info("MainActivity", "engine enable sent (rotation=$rotation)")
        }
    }

    private fun currentRotation(): Int = try {
        val dm = getSystemService(DisplayManager::class.java)
        dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    } catch (t: Throwable) {
        Surface.ROTATION_0
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val rotation = currentRotation()
            LogBuffer.info("MainActivity", "display rotation changed -> $rotation")
            postEngineIo("setRotation($rotation)") { ShizukuHelper.setRotation(rotation) }
        }
    }

    private fun requestDisable(reason: String) {
        LogBuffer.warn("MainActivity", "user requested DISABLE ($reason)")
        prefs.enabled = false
        stopActivityHeartbeat()
        stopSensiService()
        if (testing) cancelTest("người dùng tắt")
        ShizukuHelper.setEnabled(false)
        tvService.text = getString(R.string.state_idle)
        updateModeUi()
    }

    private fun emergencyRelease(reason: String) {
        LogBuffer.warn("MainActivity", "EMERGENCY RELEASE: $reason")
        prefs.enabled = false
        // Per spec: an emergency release also turns off per-app mode until re-enabled.
        prefs.onlyInSelectedApps = false
        testing = false
        testSecondsLeft = 0
        stopActivityHeartbeat()
        val ok = ShizukuHelper.emergencyRelease(reason)
        stopSensiService()
        revertEnableSwitch()
        updateModeUi()
        toast(
            if (ok) getString(R.string.released_toast)
            else getString(R.string.released_no_service)
        )
    }

    private fun ensureServiceThen(
        attempts: Int = 0,
        onReady: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        if (ShizukuHelper.service != null) {
            onReady()
            return
        }
        ShizukuHelper.ensureBound()
        if (attempts >= 10) {
            onFailed(getString(R.string.service_connect_failed))
            return
        }
        handler.postDelayed({
            ensureServiceThen(attempts + 1, onReady, onFailed)
        }, 400)
    }

    // ------------------------------------------------------------------
    // 10-second safe test
    // ------------------------------------------------------------------

    private fun startTenSecondTest() {
        if (testing) {
            cancelTest("người dùng dừng")
            return
        }
        if (prefs.enabled) {
            toast(getString(R.string.test_turn_off_first))
            return
        }
        if (ShizukuHelper.state != ShizukuHelper.State.READY) {
            toast(getString(R.string.shizuku_not_ready))
            return
        }
        ensureServiceThen(
            onReady = {
                testing = true
                testSecondsLeft = 10
                prefs.enabled = false
                enableEngineAsync(prefs.factorX, prefs.factorY)
                startActivityHeartbeat()
                btnTest.text = getString(R.string.test_stop)
                updateTestCountdown()
                LogBuffer.info("MainActivity", "10s test started (grab for 10 seconds)")
            },
            onFailed = { toast(getString(R.string.service_connect_failed)) }
        )
    }

    private fun updateTestCountdown() {
        if (!testing) return
        tvTestResult.text = getString(R.string.test_running, testSecondsLeft)
        handler.postDelayed({
            if (!testing) return@postDelayed
            testSecondsLeft--
            if (testSecondsLeft <= 0) {
                finishTest()
            } else {
                updateTestCountdown()
            }
        }, 1000)
    }

    private fun finishTest() {
        testing = false
        ShizukuHelper.setEnabled(false)
        stopActivityHeartbeat()
        prefs.testDoneThisSession = true
        btnTest.text = getString(R.string.test_start)
        tvTestResult.text = getString(R.string.test_done)
        LogBuffer.info("MainActivity", "10s test finished, grab released")
    }

    private fun cancelTest(reason: String) {
        if (!testing) return
        testing = false
        ShizukuHelper.setEnabled(false)
        stopActivityHeartbeat()
        btnTest.text = getString(R.string.test_start)
        tvTestResult.text = getString(R.string.test_cancelled, reason)
        LogBuffer.warn("MainActivity", "10s test cancelled: $reason")
    }

    // ------------------------------------------------------------------
    // Heartbeat while the activity is visible
    // ------------------------------------------------------------------

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!prefs.enabled && !testing) return
            ShizukuHelper.heartbeat()
            handler.postDelayed(this, 1000)
        }
    }

    private fun startActivityHeartbeat() {
        handler.removeCallbacks(heartbeatRunnable)
        handler.post(heartbeatRunnable)
        LogBuffer.info("MainActivity", "activity heartbeat started")
    }

    private fun stopActivityHeartbeat() {
        handler.removeCallbacks(heartbeatRunnable)
    }

    // ------------------------------------------------------------------
    // Foreground service
    // ------------------------------------------------------------------

    private fun startSensiService() {
        try {
            val intent = Intent(this, SensiService::class.java).setAction(Actions.START_SERVICE)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            LogBuffer.info("MainActivity", "SensiService start requested")
        } catch (t: Throwable) {
            LogBuffer.error("MainActivity", "startSensiService failed", t)
            toast(getString(R.string.background_start_failed))
        }
    }

    private fun stopSensiService() {
        try {
            stopService(Intent(this, SensiService::class.java))
        } catch (t: Throwable) {
            LogBuffer.error("MainActivity", "stopSensiService failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    private fun runDiagnostics() {
        toast(getString(R.string.diagnose_running))
        Thread {
            val report = buildDiagnosticReport()
            runOnUiThread { showDiagnosticDialog(report) }
        }.start()
    }

    private fun buildDiagnosticReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== SensiTune diagnostics ===")
        sb.appendLine("time: ${java.util.Date()}")
        sb.appendLine("model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        sb.appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString(",")}")
        sb.appendLine("shizuku: ${ShizukuHelper.state}, version=${ShizukuHelper.shizukuVersion}")
        sb.appendLine("serviceBound: ${ShizukuHelper.serviceBound}")
        if (ShizukuHelper.state != ShizukuHelper.State.READY || ShizukuHelper.service == null) {
            ShizukuHelper.ensureBound()
            var waited = 0
            while (ShizukuHelper.service == null && waited < 3000) {
                Thread.sleep(150)
                waited += 150
            }
        }
        val json = ShizukuHelper.diagnoseJson()
        sb.appendLine("native diagnose:")
        if (json == null) {
            sb.appendLine("  <service not connected>")
            return sb.toString()
        }
        try {
            val o = JSONObject(json)
            val pretty = o.toString(2)
            sb.appendLine(pretty)
            val uinputOk = o.optBoolean("uinputOpenable", false)
            sb.appendLine()
            if (!uinputOk) {
                sb.appendLine(
                    "KẾT LUẬN: Không mở được /dev/uinput " +
                        "(errno=${o.optInt("uinputErrno", 0)}: ${o.optString("uinputError")})."
                )
                sb.appendLine("Máy này KHÔNG hỗ trợ. App sẽ không grab cảm ứng.")
                sb.appendLine("Gợi ý: kiểm tra Shizuku đã chạy bằng ADB/shell chưa, SELinux (getenforce),")
                sb.appendLine("hoặc ROM đã chặn uinput. Có thể thử app khác/thiết bị khác.")
            } else if (o.optInt("touchCandidates") == 0 &&
                o.optJSONArray("touchCandidates")?.length() == 0
            ) {
                sb.appendLine("KẾT LUẬN: Không quét được cảm ứng. App sẽ không grab.")
            } else {
                sb.appendLine("KẾT LUẬN: uinput mở được, có cảm ứng ứng viên.")
                sb.appendLine("Hãy thử nút 'Thử 10 giây' trước khi bật thật.")
            }
        } catch (t: Throwable) {
            sb.appendLine("json parse failed: ${t.message}")
            sb.appendLine(json)
        }
        return sb.toString()
    }

    private fun showDiagnosticDialog(report: String) {
        val tv = TextView(this).apply {
            text = report
            movementMethod = ScrollingMovementMethod()
            setPadding(32, 16, 32, 16)
            textSize = 11f
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnose_title)
            .setView(tv)
            .setPositiveButton(R.string.copy_all) { _, _ ->
                copyToClipboard(report)
                toast(getString(R.string.copied))
            }
            .setNeutralButton(R.string.share) { _, _ -> shareText(report) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("SensiTune", text))
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "SensiTune diagnostics")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    // ------------------------------------------------------------------
    // Usage access
    // ------------------------------------------------------------------

    private fun hasUsageAccess(): Boolean {
        return try {
            val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            ) == android.app.AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            false
        }
    }

    private fun openUsageAccessSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            toast(getString(R.string.usage_settings_failed))
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 5001)
        }
    }

    // ------------------------------------------------------------------
    // Status polling
    // ------------------------------------------------------------------

    private val statusRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateStatus() {
        val status = ShizukuHelper.statusJson()
        if (status == null) {
            nullStatusCount++
            if (nullStatusCount > 3 && prefs.enabled && !enableInProgress && !testing) {
                LogBuffer.warn(
                    "MainActivity",
                    "no service for >3s - persisted enabled flag is stale, resetting"
                )
                prefs.enabled = false
                stopActivityHeartbeat()
                revertEnableSwitch()
            }
            tvEngineStatus.text = getString(R.string.state_no_service)
            updateShizukuUi()
            updateModeUi()
            return
        }
        nullStatusCount = 0
        try {
            val o = JSONObject(status)
            val state = o.optString("state", "?")
            val device = o.optString("device", "-")
            val deviceName = o.optString("deviceName", "-")
            val eps = o.optLong("eventsPerSec", 0)
            val latAvg = o.optLong("latencyAvgUs", 0)
            val latMax = o.optLong("latencyMaxUs", 0)
            val hb = o.optLong("lastHeartbeatAgoMs", -1)
            val dropped = o.optLong("packetsDropped", 0)
            val eagain = o.optLong("eagain", 0)

            tvEngineStatus.text = getString(
                R.string.engine_status_line,
                state, device, eps, latAvg, latMax
            )
            tvStatusDetail.text = getString(
                R.string.engine_status_detail,
                o.optInt("uid", -1),
                o.optDouble("factorX", 1.0),
                o.optDouble("factorY", 1.0),
                hb, dropped, eagain,
                o.optString("lastError", "")
            )
            tvService.text = when (state) {
                "ACTIVE" -> getString(R.string.state_active)
                "READY" -> getString(R.string.state_ready)
                "ERROR" -> getString(R.string.state_error)
                else -> getString(R.string.state_idle)
            }
            if (state == "ACTIVE" && !swEnable.isChecked && !testing) {
                suppressSwitchCallbacks = true
                swEnable.isChecked = true
                suppressSwitchCallbacks = false
            }
            if (state == "ERROR" && prefs.enabled && !testing) {
                val msg = o.optString("lastError", "")
                if (msg.isNotBlank()) {
                    tvService.text = getString(R.string.state_error_detail, msg)
                }
            }
            if (ShizukuHelper.serviceBound) {
                btnReconnect.visibility = View.GONE
            }
        } catch (t: Throwable) {
            LogBuffer.error("MainActivity", "status parse failed", t)
            tvEngineStatus.text = getString(R.string.state_status_parse_error)
        }
        updateShizukuUi()
        updateModeUi()
    }

    private fun refreshShizukuUi() {
        updateShizukuUi()
        updateModeUi()
    }

    private fun updateShizukuUi() {
        val state = ShizukuHelper.state
        val text = when (state) {
            ShizukuHelper.State.NOT_INSTALLED -> getString(R.string.shizuku_not_installed)
            ShizukuHelper.State.NOT_RUNNING -> getString(R.string.shizuku_not_running)
            ShizukuHelper.State.NO_PERMISSION -> getString(R.string.shizuku_no_permission)
            ShizukuHelper.State.READY -> getString(
                R.string.shizuku_ready,
                ShizukuHelper.shizukuVersion.ifBlank { "?" }
            )
        }
        tvShizuku.text = text
        btnReconnect.visibility =
            if (state == ShizukuHelper.State.READY && ShizukuHelper.serviceBound) View.GONE
            else View.VISIBLE
        if (ShizukuHelper.lastError != null && state != ShizukuHelper.State.READY) {
            tvService.text = ShizukuHelper.lastError
        } else if (ShizukuHelper.serviceBound) {
            tvService.text = getString(R.string.service_connected)
        } else if (state == ShizukuHelper.State.READY) {
            tvService.text = getString(R.string.service_not_connected)
        } else {
            tvService.text = getString(R.string.service_unavailable)
        }
    }

    private fun updateModeUi() {
        val fg = SensiService.currentForeground
        tvForeground.text = if (prefs.onlyInSelectedApps) {
            getString(R.string.foreground_current, fg ?: getString(R.string.foreground_none))
        } else {
            getString(R.string.foreground_off)
        }
        val usageOk = hasUsageAccess()
        tvUsageWarning.visibility =
            if (prefs.onlyInSelectedApps && !usageOk) View.VISIBLE else View.GONE
        if (swOnlyApps.isChecked != prefs.onlyInSelectedApps) {
            suppressSwitchCallbacks = true
            swOnlyApps.isChecked = prefs.onlyInSelectedApps
            suppressSwitchCallbacks = false
        }
        if (swBackground.isChecked != prefs.runBackground) {
            suppressSwitchCallbacks = true
            swBackground.isChecked = prefs.runBackground
            suppressSwitchCallbacks = false
        }
        if (prefs.enabled && swEnable.isChecked != true && !testing) {
            suppressSwitchCallbacks = true
            swEnable.isChecked = true
            suppressSwitchCallbacks = false
        }
        if (!prefs.enabled && !testing && !enableInProgress && swEnable.isChecked) {
            suppressSwitchCallbacks = true
            swEnable.isChecked = false
            suppressSwitchCallbacks = false
        }
        btnTest.isEnabled = ShizukuHelper.state == ShizukuHelper.State.READY
        btnEmergency.isEnabled = true
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        try {
            getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, handler)
        } catch (t: Throwable) {
            LogBuffer.error("MainActivity", "registerDisplayListener failed", t)
        }
        handler.post(statusRunnable)
        ShizukuHelper.addListener(this)
        ShizukuHelper.refreshState()
        if (prefs.enabled && !prefs.runBackground && !prefs.onlyInSelectedApps) {
            startActivityHeartbeat()
        }
        updateModeUi()
    }

    override fun onStop() {
        super.onStop()
        try {
            getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        } catch (t: Throwable) {
            LogBuffer.error("MainActivity", "unregisterDisplayListener failed", t)
        }
        handler.removeCallbacks(statusRunnable)
        if (prefs.enabled && !prefs.runBackground && !prefs.onlyInSelectedApps) {
            LogBuffer.info("MainActivity", "leaving app without background mode -> releasing")
            prefs.enabled = false
            stopActivityHeartbeat()
            ShizukuHelper.setEnabled(false)
            suppressSwitchCallbacks = true
            swEnable.isChecked = false
            suppressSwitchCallbacks = false
        }
        if (testing) cancelTest("rời màn hình")
        stopActivityHeartbeat()
    }

    override fun onDestroy() {
        ShizukuHelper.removeListener(this)
        handler.removeCallbacksAndMessages(null)
        engineIoHandler?.removeCallbacksAndMessages(null)
        engineIoThread?.quitSafely()
        engineIoThread = null
        engineIoHandler = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        LogBuffer.info(
            "MainActivity",
            "permission result request=$requestCode results=${grantResults.joinToString()}"
        )
    }

    override fun onResume() {
        super.onResume()
        updateModeUi()
        updateOverlayUi()
        if ((prefs.hudEnabled || prefs.floatMenuEnabled) && OverlayUi.canDraw(this)) {
            startSensiService()
        }
    }

    // ------------------------------------------------------------------
    // ShizukuHelper.Listener
    // ------------------------------------------------------------------

    override fun onShizukuStateChanged(state: ShizukuHelper.State) {
        runOnUiThread {
            updateShizukuUi()
            updateModeUi()
            LogBuffer.info("MainActivity", "Shizuku state callback: $state")
            if (state == ShizukuHelper.State.READY && ShizukuHelper.service == null) {
                ShizukuHelper.ensureBound()
            }
        }
    }

    override fun onServiceChanged(bound: Boolean) {
        runOnUiThread {
            updateShizukuUi()
            if (bound) {
                tvService.text = getString(R.string.service_connected)
            } else {
                tvService.text = getString(R.string.service_not_connected)
                if (prefs.enabled) {
                    LogBuffer.warn(
                        "MainActivity",
                        "service died while enabled - engine process died, grab already released"
                    )
                    prefs.enabled = false
                    stopActivityHeartbeat()
                    suppressSwitchCallbacks = true
                    swEnable.isChecked = false
                    suppressSwitchCallbacks = false
                }
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
