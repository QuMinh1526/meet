package com.example.sensitune

import android.content.Context
import android.os.Build
import android.os.Process
import com.example.sensitune.ISensiService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Runs inside the shell process (uid 2000) started by Shizuku.
 * All AIDL calls are guarded: nothing is thrown back to the app.
 */
class SensiUserService(private val context: Context) : ISensiService.Stub() {

    private val lock = Any()
    private var libLoaded = false
    private var libError: String? = null
    private var lastNativeError = ""

    /**
     * Native log cursor, kept server-side because the merged cursor reported by the
     * client is dominated by Kotlin entries (offset 1_000_000) and would otherwise
     * filter out every native log after the first poll.
     */
    private var lastNativeServed = 0L

    /** Dedupe for a corrupt native log payload: log once, then only count. */
    private var badNativeLogHash = Int.MIN_VALUE
    private var badNativeLogCount = 0

    /** HUD sampling lives in the shell process; this never touches the engine. */
    private val perf = PerfSampler()

    init {
        LogBuffer.info(
            TAG,
            "service created: uid=${Process.myUid()}, pid=${Process.myPid()}, " +
                "android=${Build.VERSION.RELEASE}, sdk=${Build.VERSION.SDK_INT}, " +
                "abi=${Build.SUPPORTED_ABIS.joinToString(",")}"
        )
        if (Process.myUid() != 2000) {
            LogBuffer.warn(TAG, "WARNING: uid is ${Process.myUid()}, expected 2000 (shell)")
        }
    }

    override fun setNativeLibraryPath(path: String) {
        synchronized(lock) {
            if (libLoaded) {
                LogBuffer.info(TAG, "native library already loaded")
                return
            }
            try {
                val dir = File(path)
                val so = File(dir, "libsensi_engine.so")
                LogBuffer.info(
                    TAG,
                    "nativeLibraryDir=$path (dirExists=${dir.exists()}, " +
                        "dirCanRead=${dir.canRead()})"
                )
                LogBuffer.info(
                    TAG,
                    "loading ${so.absolutePath} (exists=${so.exists()}, " +
                        "canRead=${so.canRead()}, " +
                        "size=${if (so.exists()) so.length() else -1})"
                )
                if (!so.exists()) {
                    // Make the failure diagnosable: list what IS in the directory.
                    val listing = dir.listFiles()?.joinToString(", ") { it.name } ?: "<unreadable>"
                    libError = "Không thấy ${so.absolutePath}. Nội dung thư mục: [$listing]"
                    LogBuffer.error(TAG, libError!!)
                    return
                }
                if (!so.canRead()) {
                    libError = "Không đọc được ${so.absolutePath} (permission denied)"
                    LogBuffer.error(TAG, libError!!)
                    return
                }
                System.load(so.absolutePath)
                libLoaded = true
                LogBuffer.info(TAG, "native library loaded, engine version=${NativeBridge.nativeVersion()}")
            } catch (t: UnsatisfiedLinkError) {
                libLoaded = false
                libError = "UnsatisfiedLinkError: ${t.message}"
                LogBuffer.error(TAG, "System.load failed", t)
            } catch (t: Throwable) {
                libLoaded = false
                libError = "${t.javaClass.simpleName}: ${t.message}"
                LogBuffer.error(TAG, "System.load failed", t)
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            LogBuffer.info(TAG, "setEnabled($enabled) requested")
            if (!ensureLib()) return
            try {
                val code = NativeBridge.setEnabled(enabled)
                if (code != NativeBridge.OK) {
                    lastNativeError = NativeBridge.errorMessage(code)
                    LogBuffer.error(TAG, "setEnabled failed: $lastNativeError")
                } else {
                    lastNativeError = ""
                    LogBuffer.info(TAG, "engine state now: ${nativeState()}")
                }
            } catch (t: Throwable) {
                lastNativeError = "exception: ${t.javaClass.simpleName}: ${t.message}"
                LogBuffer.error(TAG, "setEnabled exception", t)
            }
        }
    }

    override fun setFactor(x: Float, y: Float) {
        synchronized(lock) {
            if (!ensureLib()) return
            try {
                val code = NativeBridge.setFactor(x, y)
                if (code != NativeBridge.OK) {
                    lastNativeError = NativeBridge.errorMessage(code)
                    LogBuffer.warn(TAG, "setFactor($x, $y) rejected: $lastNativeError")
                }
            } catch (t: Throwable) {
                LogBuffer.error(TAG, "setFactor exception", t)
            }
        }
    }

    override fun heartbeat() {
        if (!libLoaded) return
        try {
            NativeBridge.heartbeat()
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "heartbeat exception", t)
        }
    }

    override fun getStatus(): String {
        synchronized(lock) {
            val obj = JSONObject()
            try {
                if (libLoaded) {
                    val native = JSONObject(NativeBridge.getStatus())
                    val it = native.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        obj.put(k, native.get(k))
                    }
                } else {
                    obj.put("state", "ERROR")
                    obj.put("enabled", false)
                    obj.put("grab", false)
                }
            } catch (t: Throwable) {
                LogBuffer.error(TAG, "getStatus native failed", t)
                try {
                    obj.put("state", "ERROR")
                } catch (_: Throwable) {
                }
            }
            try {
                obj.put("uid", Process.myUid())
                obj.put("pid", Process.myPid())
                obj.put("libLoaded", libLoaded)
                obj.put("libError", libError ?: "")
                obj.put("lastNativeError", lastNativeError)
                obj.put("serviceVersion", BuildConfig.VERSION_CODE)
                obj.put("androidVersion", Build.VERSION.RELEASE)
                obj.put("sdk", Build.VERSION.SDK_INT)
                obj.put("model", Build.MODEL)
                obj.put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
            } catch (t: Throwable) {
                LogBuffer.error(TAG, "getStatus merge failed", t)
            }
            return obj.toString()
        }
    }

    override fun getLogs(sinceSeq: Int): String {
        val merged = ArrayList<JSONObject>()
        try {
            if (libLoaded) {
                val nativeSince = when {
                    sinceSeq <= 0 -> 0L
                    sinceSeq >= LogBuffer.SEQ_OFFSET -> lastNativeServed
                    else -> sinceSeq.toLong()
                }
                val raw = NativeBridge.getLogs(nativeSince.toInt())
                try {
                    val nativeArr = JSONArray(raw)
                    for (i in 0 until nativeArr.length()) {
                        val o = nativeArr.getJSONObject(i)
                        val seq = o.optLong("seq", 0L)
                        if (seq > lastNativeServed) lastNativeServed = seq
                        merged.add(o)
                    }
                    badNativeLogHash = Int.MIN_VALUE
                    badNativeLogCount = 0
                } catch (t: Throwable) {
                    handleBadNativeLogs(raw, t, merged)
                }
            }
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "getLogs native failed", t)
        }
        try {
            val sinceLocal = if (sinceSeq >= LogBuffer.SEQ_OFFSET) {
                sinceSeq - LogBuffer.SEQ_OFFSET
            } else {
                0L
            }
            for (e in LogBuffer.snapshot(sinceLocal)) {
                merged.add(
                    JSONObject()
                        .put("seq", e.seq)
                        .put("ts", e.ts)
                        .put("level", e.level)
                        .put("thread", e.thread)
                        .put("msg", e.msg)
                )
            }
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "getLogs local failed", t)
        }
        merged.sortBy { it.optLong("ts", 0L) }
        val arr = JSONArray()
        merged.forEach { arr.put(it) }
        return arr.toString()
    }

    /**
     * The native ring is expected to be valid JSON. If it is not, surface the
     * raw payload once (so the log screen stays useful) and only count repeats
     * instead of writing the same error every poll.
     */
    private fun handleBadNativeLogs(
        raw: String,
        t: Throwable,
        merged: ArrayList<JSONObject>
    ) {
        val hash = raw.hashCode()
        val type = t.javaClass.simpleName
        if (hash != badNativeLogHash) {
            badNativeLogHash = hash
            badNativeLogCount = 1
            LogBuffer.error(
                TAG,
                "getLogs: native JSON invalid ($type: ${t.message}); raw=${raw.take(400)}"
            )
            try {
                merged.add(
                    JSONObject()
                        .put("seq", lastNativeServed)
                        .put("ts", System.currentTimeMillis())
                        .put("level", LogBuffer.LEVEL_ERROR)
                        .put("thread", TAG)
                        .put("msg", "JSON log native lỗi ($type): ${raw.take(600)}")
                )
            } catch (_: Throwable) {
                // Never let a diagnostic entry break getLogs().
            }
        } else {
            badNativeLogCount++
            if (badNativeLogCount == 2 || badNativeLogCount % 10 == 0) {
                LogBuffer.warn(
                    TAG,
                    "getLogs: native JSON vẫn lỗi, lặp ${badNativeLogCount} lần"
                )
            }
        }
    }

    override fun emergencyRelease() {
        LogBuffer.warn(TAG, "emergencyRelease() requested")
        try {
            if (libLoaded) {
                NativeBridge.emergencyRelease("user emergency button")
            }
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "emergencyRelease failed", t)
        }
    }

    override fun destroy() {
        LogBuffer.warn(TAG, "destroy() requested - releasing everything")
        try {
            if (libLoaded) {
                NativeBridge.stop("service destroy")
            }
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "destroy native stop failed", t)
        } finally {
            LogBuffer.info(TAG, "service destroyed")
        }
    }

    override fun diagnose(): String {
        val obj = JSONObject()
        try {
            if (libLoaded) {
                val native = JSONObject(NativeBridge.diagnose())
                val it = native.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    obj.put(k, native.get(k))
                }
            } else {
                obj.put("uinputOpenable", false)
                obj.put("uinputError", libError ?: "native library not loaded")
            }
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "diagnose failed", t)
            try {
                obj.put("uinputOpenable", false)
                obj.put("uinputError", "${t.javaClass.simpleName}: ${t.message}")
            } catch (_: Throwable) {
            }
        }
        try {
            obj.put("uid", Process.myUid())
            obj.put("androidVersion", Build.VERSION.RELEASE)
            obj.put("sdk", Build.VERSION.SDK_INT)
            obj.put("model", Build.MODEL)
            obj.put("manufacturer", Build.MANUFACTURER)
            obj.put("device", Build.DEVICE)
            obj.put("abi", Build.SUPPORTED_ABIS.joinToString(","))
            obj.put("libLoaded", libLoaded)
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "diagnose merge failed", t)
        }
        return obj.toString()
    }

    override fun configurePerf(metricsCsv: String, foregroundPackage: String) {
        try {
            perf.configure(metricsCsv, foregroundPackage)
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "configurePerf failed", t)
        }
    }

    override fun getPerfSnapshot(): String {
        return try {
            val json = perf.snapshot()
            try {
                val o = JSONObject(json)
                o.put("uid", Process.myUid())
                o.toString()
            } catch (_: Throwable) {
                json
            }
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "getPerfSnapshot failed", t)
            try {
                JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("errors", JSONArray().put("snapshot"))
                    .toString()
            } catch (_: Throwable) {
                "{\"errors\":[\"snapshot\"]}"
            }
        }
    }

    private fun ensureLib(): Boolean {
        if (libLoaded) return true
        lastNativeError = "native library chưa load: ${libError ?: "unknown"}"
        LogBuffer.error(TAG, lastNativeError)
        return false
    }

    private fun nativeState(): String = try {
        JSONObject(NativeBridge.getStatus()).optString("state", "?")
    } catch (t: Throwable) {
        "?"
    }

    private companion object {
        const val TAG = "SensiUserService"
    }
}
