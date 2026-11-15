package com.example.sensitune

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs inside the Shizuku UserService (shell, uid 2000) and collects the metrics
 * requested by the app HUD. Everything is best-effort: a metric that cannot be
 * read is simply omitted (and reported under "errors"), never thrown.
 *
 * Thermal zones are scanned exactly once and their file paths are cached, so a
 * zone renumbering after reboot cannot point us at the wrong sensor. Zone names
 * containing step/lvl/lowf are skipped, as are unreadable files and values
 * outside 0..120 °C (some zones return -40000 when unsupported).
 */
class PerfSampler {

    private data class Zone(val type: String, val path: String)

    private val logged = HashSet<String>()
    private val zonesLock = Any()
    private var zonesCache: List<Zone>? = null

    @Volatile
    private var wanted: Set<String> = emptySet()

    @Volatile
    private var configuredPkg: String = ""

    // /proc/stat delta for CPU %
    private var lastTotal = -1L
    private var lastIdle = -1L

    // FPS caches
    private var fgPkg: String? = null
    private var fgAt = 0L
    private var layerPkg: String? = null
    private var layerName: String? = null
    private var fpsNote: String = ""
    private val execLock = Any()

    fun configure(metricsCsv: String, foregroundPackage: String) {
        wanted = metricsCsv.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (foregroundPackage != configuredPkg) {
            configuredPkg = foregroundPackage
            if (layerPkg != null && layerPkg != foregroundPackage) {
                layerName = null
                layerPkg = null
            }
        }
    }

    @Synchronized
    fun snapshot(): String {
        val start = SystemClock.elapsedRealtime()
        val o = JSONObject()
        val errors = JSONArray()
        val w = wanted
        try {
            o.put("ts", System.currentTimeMillis())
            if (w.isEmpty()) {
                o.put("errors", errors)
                o.put("sampleMs", 0)
                return o.toString()
            }

            if ("cpu" in w) {
                val v = maxTemp { it.matches(CPU_ZONE_1) || it.matches(CPU_ZONE_2) }
                if (v != null) o.put("cpuTemp", round1(v)) else errors.put("cpuTemp")
            }
            if ("gpu" in w) {
                val v = maxTemp { it.matches(GPU_ZONE) }
                if (v != null) o.put("gpuTemp", round1(v)) else errors.put("gpuTemp")
            }
            if ("battery" in w) {
                val v = exactTemp("battery") ?: batteryFromDumpsys()
                if (v != null) o.put("batteryTemp", round1(v)) else errors.put("batteryTemp")
            }
            if ("ram" in w) {
                val v = maxTemp { it.matches(DDR_ZONE) }
                if (v != null) o.put("ramTemp", round1(v)) else errors.put("ramTemp")
            }
            if ("skin" in w) {
                val v = exactTemp("skin-therm")
                if (v != null) o.put("skinTemp", round1(v)) else errors.put("skinTemp")
            }
            if ("shell" in w) {
                val v = exactTemp("shell_back")
                if (v != null) o.put("shellTemp", round1(v)) else errors.put("shellTemp")
            }
            if ("cpuPct" in w) {
                val v = cpuPercent()
                if (v != null) o.put("cpuPct", round1(v)) else errors.put("cpuPct")
            }
            if ("cpuFreq" in w) {
                val khz = maxCpuFreqKhz()
                if (khz != null) o.put("cpuFreqMhz", khz / 1000) else errors.put("cpuFreq")
            }
            if ("fps" in w) {
                fpsNote = ""
                val v = fps()
                if (v != null) o.put("fps", round1(v)) else errors.put("fps")
                if (fpsNote.isNotEmpty()) o.put("fpsNote", fpsNote)
            }

            o.put("errors", errors)
            o.put("sampleMs", SystemClock.elapsedRealtime() - start)
        } catch (t: Throwable) {
            logOnce("snapshot", "perf snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
            try {
                o.put("errors", JSONArray().put("snapshot"))
            } catch (_: Throwable) {
            }
        }
        return o.toString()
    }

    // ------------------------------------------------------------------
    // Thermal
    // ------------------------------------------------------------------

    private fun zones(): List<Zone> = synchronized(zonesLock) {
        zonesCache?.let { return it }
        val out = ArrayList<Zone>()
        val dir = File("/sys/class/thermal")
        val names = try {
            dir.list()
        } catch (t: Throwable) {
            logOnce("thermaldir", "cannot list /sys/class/thermal: ${t.message}")
            null
        } ?: emptyArray()
        for (name in names) {
            if (!name.startsWith("thermal_zone")) continue
            val base = File(dir, name)
            val type = try {
                File(base, "type").readText().trim()
            } catch (t: Throwable) {
                logOnce("type:$name", "skip $name: cannot read type (${t.javaClass.simpleName})")
                continue
            }
            if (type.isEmpty() || type.contains("step") || type.contains("lvl") ||
                type.contains("lowf")
            ) {
                continue
            }
            val temp = File(base, "temp")
            if (!temp.canRead()) {
                logOnce("temp:$name", "skip $name ($type): temp not readable")
                continue
            }
            out.add(Zone(type, temp.absolutePath))
        }
        LogBuffer.info(TAG, "thermal zones cached: ${out.joinToString { "${it.type}" }}")
        zonesCache = out
        return out
    }

    private fun readZone(z: Zone): Double? {
        val raw = try {
            File(z.path).readText().trim()
        } catch (t: Throwable) {
            logOnce("read:${z.type}", "cannot read ${z.path}: ${t.javaClass.simpleName}")
            return null
        }
        val milli = raw.toIntOrNull()
        if (milli == null || milli !in 0..120_000) {
            logOnce("value:${z.type}", "skip ${z.type}: implausible temp '$raw'")
            return null
        }
        return milli / 1000.0
    }

    private fun maxTemp(pred: (String) -> Boolean): Double? {
        var max: Double? = null
        for (z in zones()) {
            if (!pred(z.type)) continue
            val v = readZone(z) ?: continue
            if (max == null || v > max) max = v
        }
        return max
    }

    private fun exactTemp(type: String): Double? {
        val z = zones().firstOrNull { it.type == type } ?: return null
        return readZone(z)
    }

    private fun batteryFromDumpsys(): Double? {
        val text = dumpsys("battery") ?: return null
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("temperature:")) continue
            val tenths = t.substringAfter(':').trim().toIntOrNull() ?: continue
            if (tenths in 0..1200) return tenths / 10.0
        }
        logOnce("battery", "dumpsys battery has no usable temperature")
        return null
    }

    // ------------------------------------------------------------------
    // CPU
    // ------------------------------------------------------------------

    private fun cpuPercent(): Double? {
        val line = try {
            File("/proc/stat").useLines { seq ->
                seq.firstOrNull { it.startsWith("cpu ") }
            }
        } catch (t: Throwable) {
            logOnce("procstat", "cannot read /proc/stat: ${t.message}")
            null
        } ?: return null
        val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (parts.size < 4) return null
        val idle = parts[3] + (parts.getOrNull(4) ?: 0L)
        val total = parts.sum()
        val dTotal = if (lastTotal < 0) -1 else total - lastTotal
        val dIdle = if (lastIdle < 0) -1 else idle - lastIdle
        lastTotal = total
        lastIdle = idle
        if (dTotal <= 0 || dIdle < 0) return null
        return (100.0 * (dTotal - dIdle) / dTotal).coerceIn(0.0, 100.0)
    }

    private fun maxCpuFreqKhz(): Long? {
        val dir = File("/sys/devices/system/cpu")
        val cpus = dir.listFiles { f -> f.name.matches(Regex("cpu\\d+")) } ?: return null
        var max = -1L
        for (cpu in cpus) {
            val f = File(cpu, "cpufreq/scaling_cur_freq")
            if (!f.canRead()) continue
            val khz = try {
                f.readText().trim().toLongOrNull()
            } catch (t: Throwable) {
                null
            } ?: continue
            if (khz > max) max = khz
        }
        return if (max > 0) max else null
    }

    // ------------------------------------------------------------------
    // FPS (SurfaceFlinger)
    // ------------------------------------------------------------------

    private fun fps(): Double? {
        val nowMs = SystemClock.elapsedRealtime()
        var pkg = configuredPkg.trim().ifEmpty { null }
        if (pkg == null) {
            if (fgPkg == null || nowMs - fgAt > FG_CACHE_MS) {
                fgPkg = detectForegroundPkg()
                fgAt = nowMs
                if (fgPkg == null) {
                    logOnce("fg", "fps: cannot determine foreground package")
                }
            }
            pkg = fgPkg
        }
        if (pkg == null) {
            fpsNote = "không xác định được app foreground"
            return null
        }

        var layer = layerName?.takeIf { layerPkg == pkg }
        if (layer == null) {
            val list = dumpsys("SurfaceFlinger", "--list")
            if (list == null) {
                fpsNote = "--list timeout"
                return null
            }
            val candidates = list.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.contains(pkg) }
                .toList()
            layer = candidates.firstOrNull { !it.startsWith("Background for") }
                ?: candidates.firstOrNull()
            if (layer == null) {
                layerPkg = pkg
                layerName = null
                fpsNote = "không thấy layer của $pkg"
                logOnce("layer:$pkg", "fps: no SurfaceFlinger layer contains '$pkg'")
                return null
            }
            layerPkg = pkg
            layerName = layer
            LogBuffer.info(TAG, "fps: using layer \"$layer\" for $pkg")
        }

        val out = dumpsys("SurfaceFlinger", "--latency", layer)
        if (out == null) {
            fpsNote = "--latency timeout"
            return null
        }
        val nowNs = System.nanoTime()
        var valid = 0
        var counted = 0
        for (line in out.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3) continue
            val actual = parts[1].toLongOrNull() ?: continue
            if (actual <= 0 || actual >= Long.MAX_VALUE / 2) continue
            valid++
            val age = nowNs - actual
            if (age in 0..1_000_000_000L) counted++
        }
        if (valid == 0) {
            fpsNote = "không có timestamp present"
            return null
        }
        return counted.toDouble()
    }

    private fun detectForegroundPkg(): String? {
        val text = dumpsys("activity", "activities") ?: return null
        val patterns = listOf(
            Regex("mResumedActivity.*?([A-Za-z0-9_.]+)/"),
            Regex("topResumedActivity.*?([A-Za-z0-9_.]+)/")
        )
        for (re in patterns) {
            val m = re.find(text) ?: continue
            val pkg = m.groupValues.getOrNull(1) ?: continue
            if (pkg.isNotEmpty()) return pkg
        }
        return null
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Runs a command with an 800 ms budget, reading concurrently so a full pipe
     * can never block the child. Never runs two commands at once.
     */
    private fun dumpsys(vararg args: String): String? {
        val start = SystemClock.elapsedRealtime()
        synchronized(execLock) {
            var process: Process? = null
            try {
                val p = ProcessBuilder(listOf("dumpsys") + args)
                    .redirectErrorStream(true)
                    .start()
                process = p
                val sb = StringBuilder()
                val reader = Thread {
                    try {
                        p.inputStream.bufferedReader().forEachLine {
                            if (sb.length < 512 * 1024) sb.append(it).append('\n')
                        }
                    } catch (_: Throwable) {
                    }
                }
                reader.isDaemon = true
                reader.start()
                val finished = p.waitFor(CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!finished) {
                    p.destroyForcibly()
                    logOnce("timeout:${args.joinToString(" ")}",
                        "dumpsys ${args.joinToString(" ")} timed out after ${CMD_TIMEOUT_MS}ms")
                    return null
                }
                reader.join(200)
                if (p.exitValue() != 0) {
                    logOnce("exit:${args.joinToString(" ")}",
                        "dumpsys ${args.joinToString(" ")} exited ${p.exitValue()}")
                    return null
                }
                val ms = SystemClock.elapsedRealtime() - start
                if (ms > 500) {
                    LogBuffer.warn(TAG, "dumpsys ${args.joinToString(" ")} took ${ms}ms")
                }
                return sb.toString()
            } catch (t: Throwable) {
                logOnce("exec:${args.joinToString(" ")}",
                    "dumpsys ${args.joinToString(" ")} failed: ${t.javaClass.simpleName}: ${t.message}")
                try {
                    process?.destroyForcibly()
                } catch (_: Throwable) {
                }
                return null
            }
        }
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    private fun logOnce(key: String, msg: String) {
        synchronized(logged) {
            if (!logged.add(key)) return
        }
        LogBuffer.info(TAG, msg)
    }

    private companion object {
        const val TAG = "PerfSampler"
        const val CMD_TIMEOUT_MS = 800L
        const val FG_CACHE_MS = 5000L

        val CPU_ZONE_1 = Regex("^cpu-\\d-\\d-usr$")
        val CPU_ZONE_2 = Regex("^cpuss-\\d-usr$")
        val GPU_ZONE = Regex("^gpuss-\\d-usr$")
        val DDR_ZONE = Regex("^ddr-\\d-usr$")
    }
}
