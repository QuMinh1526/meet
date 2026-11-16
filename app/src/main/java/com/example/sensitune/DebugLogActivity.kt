package com.example.sensitune

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Realtime log + status table. Polls the UserService every 500 ms and merges native,
 * service-side Kotlin and activity-side Kotlin logs.
 */
class DebugLogActivity : Activity() {

    private data class Row(
        val ts: Long,
        val level: Int,
        val text: String,
        val key: String
    )

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scroll: ScrollView
    private lateinit var spLevel: Spinner

    private val handler = Handler(Looper.getMainLooper())
    private val rows = ArrayList<Row>()
    private val keys = HashSet<String>()
    private var lastServiceSeq = 0
    private var lastLocalSeq = 0L
    private var levelFilter = 0
    private var dirty = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollLogs()
            updateStatusTable()
            if (dirty) {
                dirty = false
                render()
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_log)
        setTitle(R.string.debug_title)

        tvStatus = findViewById(R.id.tvDebugStatus)
        tvLog = findViewById(R.id.tvDebugLog)
        scroll = findViewById(R.id.scrollLog)
        spLevel = findViewById(R.id.spLevel)

        tvLog.movementMethod = ScrollingMovementMethod()
        tvLog.setTextIsSelectable(true)

        val filters = arrayOf(
            getString(R.string.filter_all),
            getString(R.string.filter_info),
            getString(R.string.filter_warn),
            getString(R.string.filter_error)
        )
        spLevel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, filters)
        spLevel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                levelFilter = position
                render()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            copyToClipboard(filteredText())
            toast(getString(R.string.copied))
        }
        findViewById<Button>(R.id.btnShare).setOnClickListener {
            shareText(filteredText())
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveToFile(filteredText())
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener { clearLogs() }

        ShizukuHelper.init(this)
        ShizukuHelper.addListener(object : ShizukuHelper.Listener {
            override fun onShizukuStateChanged(state: ShizukuHelper.State) {
                updateStatusTable()
            }

            override fun onServiceChanged(bound: Boolean) {
                updateStatusTable()
            }
        })

        // Show whatever is already buffered before the first poll completes.
        pollLogs()
        render()
        updateStatusTable()
    }

    override fun onStart() {
        super.onStart()
        handler.post(pollRunnable)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(pollRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Polling
    // ------------------------------------------------------------------

    private fun pollLogs() {
        try {
            val json = ShizukuHelper.logsJson(lastServiceSeq)
            if (json != null) {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    addRow(
                        o.optLong("seq", 0L),
                        o.optLong("ts", 0L),
                        o.optInt("level", LogBuffer.LEVEL_INFO),
                        o.optString("thread", "?"),
                        o.optString("msg", ""),
                        "s"
                    )
                    if (o.optLong("seq", 0L) > lastServiceSeq) {
                        lastServiceSeq = o.getLong("seq").toInt()
                    }
                }
            }
        } catch (t: Throwable) {
            addLocalRow(
                LogBuffer.LEVEL_ERROR,
                "DebugLog",
                "Không đọc được log từ service: ${t.javaClass.simpleName}: ${t.message}"
            )
        }

        try {
            for (e in LogBuffer.snapshot(lastLocalSeq)) {
                addRow(
                    e.seq,
                    e.ts,
                    e.level,
                    e.thread,
                    e.msg,
                    "l"
                )
                if (e.seq > lastLocalSeq) lastLocalSeq = e.seq
            }
        } catch (t: Throwable) {
            android.util.Log.e("SensiTune", "local log poll failed", t)
        }
    }

    private fun addRow(
        seq: Long,
        ts: Long,
        level: Int,
        thread: String,
        msg: String,
        prefix: String
    ) {
        val key = "$prefix$seq"
        if (!keys.add(key)) return
        val time = timeFormat.format(Date(if (ts > 0) ts else System.currentTimeMillis()))
        val line = "$time ${LogBuffer.levelName(level)} [$thread] $msg"
        rows.add(Row(ts, level, line, key))
        dirty = true
    }

    private fun addLocalRow(level: Int, thread: String, msg: String) {
        val key = "x${System.nanoTime()}"
        val ts = System.currentTimeMillis()
        rows.add(Row(ts, level, "${timeFormat.format(Date(ts))} " +
            "${LogBuffer.levelName(level)} [$thread] $msg", key))
        dirty = true
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun filteredRows(): List<Row> {
        return rows.filter { row ->
            when (levelFilter) {
                1 -> row.level >= LogBuffer.LEVEL_INFO
                2 -> row.level >= LogBuffer.LEVEL_WARN
                3 -> row.level >= LogBuffer.LEVEL_ERROR
                else -> true
            }
        }.sortedBy { it.ts }
    }

    private fun filteredText(): String =
        filteredRows().joinToString("\n") { it.text }

    private fun render() {
        val list = filteredRows()
        val start = maxOf(0, list.size - 4000)
        val sb = StringBuilder()
        for (i in start until list.size) {
            sb.appendLine(list[i].text)
        }
        tvLog.text = sb.toString()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearLogs() {
        rows.clear()
        keys.clear()
        LogBuffer.clear()
        lastLocalSeq = 0
        // Advance the native ring cursor so old native entries are not re-added.
        val json = ShizukuHelper.logsJson(0)
        if (json != null) {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i).optLong("seq", 0L)
                    if (s > lastServiceSeq) lastServiceSeq = s.toInt()
                }
            } catch (t: Throwable) {
                android.util.Log.e("SensiTune", "clear logs parse failed", t)
            }
        }
        tvLog.text = ""
        toast(getString(R.string.log_cleared))
    }

    // ------------------------------------------------------------------
    // Status table
    // ------------------------------------------------------------------

    private fun updateStatusTable() {
        val sb = StringBuilder()
        sb.appendLine(getString(R.string.debug_status_title))
        sb.appendLine(
            getString(
                R.string.debug_shizuku,
                shizukuStateText(),
                ShizukuHelper.shizukuVersion.ifBlank { "?" }
            )
        )

        val status = ShizukuHelper.statusJson()
        if (status == null) {
            sb.appendLine(getString(R.string.debug_service, getString(R.string.service_unavailable)))
            sb.appendLine(getString(R.string.debug_uid, -1))
            tvStatus.text = sb.toString()
            return
        }
        try {
            val o = JSONObject(status)
            val state = o.optString("state", "?")
            val device = o.optString("device", "-")
            val deviceName = o.optString("deviceName", "-")
            val hb = o.optLong("lastHeartbeatAgoMs", -1)
            sb.appendLine(getString(R.string.debug_service, state))
            sb.appendLine(getString(R.string.debug_uid, o.optInt("uid", -1)))
            sb.appendLine(getString(R.string.debug_engine_state, state))
            sb.appendLine(getString(R.string.debug_device, "$device ($deviceName)"))
            sb.appendLine(
                getString(
                    R.string.debug_factor,
                    o.optDouble("factorX", 1.0),
                    o.optDouble("factorY", 1.0)
                )
            )
            val rotation = o.optInt("rotation", 0)
            sb.appendLine(
                getString(
                    R.string.debug_rotation,
                    rotation,
                    if (rotation == 1 || rotation == 3) {
                        getString(R.string.orientation_landscape)
                    } else {
                        getString(R.string.orientation_portrait)
                    }
                )
            )
            sb.appendLine(
                getString(
                    R.string.debug_swipe,
                    o.optInt("edgeMarginPct", 12),
                    if (o.optBoolean("softRecenter", true)) "ON" else "OFF",
                    if (o.optBoolean("suspended", false)) "ON" else "OFF"
                )
            )
            sb.appendLine(
                getString(
                    R.string.debug_mode,
                    o.optString("modeName", "SENSITIVITY"),
                    if (o.optBoolean("styleEnabled", false)) {
                        val id = o.optInt("styleId", 0)
                        FloatMenu.STYLE_NAMES.getOrElse(id) { "#$id" }
                    } else "OFF (Raw)",
                    o.optInt("styleIntensity", 0)
                )
            )
            sb.appendLine(
                getString(
                    R.string.debug_style_latency,
                    o.optLong("styleLatencyAvgUs", 0),
                    o.optLong("styleLatencyMaxUs", 0)
                )
            )
            sb.appendLine(
                getString(
                    R.string.debug_drag,
                    o.optInt("dragValue", 0),
                    o.optInt("dragShape", 0),
                    o.optInt("dragEasing", 0),
                    o.optInt("dragDirection", 0),
                    o.optInt("dragLengthRef", 220)
                )
            )
            sb.appendLine(
                getString(
                    R.string.debug_zone,
                    if (o.optBoolean("zoneValid", false)) "ON" else "OFF",
                    o.optInt("zoneCx", 0),
                    o.optInt("zoneCy", 0),
                    o.optInt("zoneR", 0),
                    o.optInt("screenW", 0),
                    o.optInt("screenH", 0),
                    o.optDouble("dragGainMax", 1.0)
                )
            )
            sb.appendLine(
                getString(
                    R.string.debug_perf,
                    o.optLong("eventsPerSec", 0),
                    o.optLong("latencyAvgUs", 0),
                    o.optLong("latencyMaxUs", 0)
                )
            )
            sb.appendLine(
                getString(
                    R.string.debug_counters,
                    o.optLong("packetsDropped", 0),
                    o.optLong("eagain", 0)
                )
            )
            sb.appendLine(
                getString(
                    R.string.debug_heartbeat,
                    if (hb < 0) "chưa có" else "$hb ms"
                )
            )
            sb.appendLine(
                getString(
                    R.string.debug_foreground,
                    SensiService.currentForeground ?: getString(R.string.foreground_none)
                )
            )
            val err = o.optString("lastError", "")
            if (err.isNotBlank()) {
                sb.appendLine(getString(R.string.debug_last_error, err))
            }
        } catch (t: Throwable) {
            sb.appendLine("status parse error: ${t.message}")
        }
        tvStatus.text = sb.toString()
    }

    private fun shizukuStateText(): String = when (ShizukuHelper.state) {
        ShizukuHelper.State.NOT_INSTALLED -> getString(R.string.shizuku_not_installed)
        ShizukuHelper.State.NOT_RUNNING -> getString(R.string.shizuku_not_running)
        ShizukuHelper.State.NO_PERMISSION -> getString(R.string.shizuku_no_permission)
        ShizukuHelper.State.READY -> getString(R.string.debug_shizuku_ok)
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SensiTune log", text))
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "SensiTune log")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun saveToFile(text: String) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val name = "sensitune-log-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
            val file = File(dir, name)
            file.writeText(text)
            toast(getString(R.string.saved_to, file.absolutePath))
            android.util.Log.i("SensiTune", "log saved to ${file.absolutePath}")
        } catch (t: Throwable) {
            toast(getString(R.string.save_failed, t.message ?: "?"))
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
