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

    /**
     * Real FPS from SurfaceFlinger.
     *
     * Layer discovery mirrors the adb recipe:
     *   dumpsys SurfaceFlinger --list | grep "<pkg>"
     * and then the latency buffer gives a refresh period on line 1 plus up to
     * 127 rows of (desiredPresentTime, actualPresentTime, frameReadyTime) in
     * nanoseconds. FPS is derived from the differences between consecutive
     * actualPresentTime values, not from "how many frames look recent".
     */
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
            layer = findLayer(pkg)
            if (layer == null) {
                fpsNote = "không thấy layer của $pkg"
                logOnce("layer:$pkg", "fps: no SurfaceFlinger layer contains '$pkg'")
                return null
            }
            layerPkg = pkg
            layerName = layer
            LogBuffer.info(TAG, "fps: using layer \"$layer\" for $pkg")
        }

        val out = shell("dumpsys SurfaceFlinger --latency ${shellQuote(layer)}")
        if (out == null) {
            fpsNote = "--latency timeout"
            return null
        }
        val parsed = parseLatency(out)
        if (parsed == null) {
            // The layer can disappear when the game restarts: drop the cache so
            // the next tick re-runs --list instead of staying broken forever.
            layerPkg = pkg
            layerName = null
            fpsNote = "không đủ timestamp present"
            logOnce("latency:$pkg", "fps: --latency of \"$layer\" had <2 usable frames")
            return null
        }
        return parsed.first
    }

    /** `dumpsys SurfaceFlinger --list | grep -F <pkg>`, with an in-process fallback. */
    private fun findLayer(pkg: String): String? {
        val safe = pkg.takeIf { PACKAGE_RE.matches(it) }
        var candidates: List<String> = emptyList()
        if (safe != null) {
            val grep = shell("dumpsys SurfaceFlinger --list | grep -F ${shellQuote(safe)}")
            if (!grep.isNullOrBlank()) {
                candidates = grep.lineSequence().map { it.trim() }
                    .filter { it.isNotEmpty() }.toList()
            }
        }
        if (candidates.isEmpty()) {
            val list = dumpsys("SurfaceFlinger", "--list")
            if (list == null) {
                fpsNote = "--list timeout"
                return null
            }
            candidates = list.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && it.contains(pkg) }.toList()
        }
        return candidates.firstOrNull { it.contains("SurfaceView") && !it.startsWith("Background for") }
            ?: candidates.firstOrNull { !it.startsWith("Background for") }
            ?: candidates.firstOrNull()
    }

    /**
     * Parses the `--latency` buffer. Returns (fps, refreshPeriodNs) or null when
     * fewer than two usable present timestamps exist. Rows whose actual time is
     * 0 or INT64_MAX are placeholders and are skipped, as are non-monotonic and
     * >500 ms gaps (app paused / buffer stale) so they cannot drag FPS down.
     */
    private fun parseLatency(out: String): Pair<Double, Long>? {
        val lines = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 3) return null
        val refreshNs = lines.firstOrNull()?.toLongOrNull() ?: 0L

        val present = ArrayList<Long>(LATENCY_ROWS)
        for (line in lines.drop(1)) {
            val parts = line.split(SPLIT_WS)
            if (parts.size < 3) continue
            val actual = parts[1].toLongOrNull() ?: continue
            if (actual <= 0 || actual >= Long.MAX_VALUE / 2) continue
            if (present.isNotEmpty() && actual <= present.last()) continue
            present.add(actual)
        }
        if (present.size < 3) return null

        var sum = 0L
        var gaps = 0
        for (i in 1 until present.size) {
            val d = present[i] - present[i - 1]
            if (d in 1..MAX_FRAME_GAP_NS) {
                sum += d
                gaps++
            }
        }
        if (gaps == 0) return null
        val avg = sum.toDouble() / gaps
        if (avg <= 0.0) return null
        val fps = 1_000_000_000.0 / avg
        if (!fps.isFinite() || fps <= 0.0 || fps > 1000.0) return null
        return Pair(fps, refreshNs)
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

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

    private fun dumpsys(vararg args: String): String? =
        runCommand(listOf("dumpsys") + args, "dumpsys ${args.joinToString(" ")}")

    /** Runs a pipeline through /system/bin/sh in the shell (uid 2000) process. */
    private fun shell(command: String): String? =
        runCommand(listOf("/system/bin/sh", "-c", command), "sh -c $command")

    /**
     * Runs a command with a 1.5 s budget, reading concurrently so a full pipe can
     * never block the child. Never runs two commands at once.
     */
    private fun runCommand(cmd: List<String>, label: String): String? {
        val start = SystemClock.elapsedRealtime()
        synchronized(execLock) {
            var process: Process? = null
            try {
                val p = ProcessBuilder(cmd)
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
                    logOnce("timeout:$label", "$label timed out after ${CMD_TIMEOUT_MS}ms")
                    return null
                }
                reader.join(200)
                if (p.exitValue() != 0) {
                    logOnce("exit:$label", "$label exited ${p.exitValue()}")
                    return null
                }
                val ms = SystemClock.elapsedRealtime() - start
                if (ms > 500) LogBuffer.warn(TAG, "$label took ${ms}ms")
                return sb.toString()
            } catch (t: Throwable) {
                logOnce("exec:$label", "$label failed: ${t.javaClass.simpleName}: ${t.message}")
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
        const val CMD_TIMEOUT_MS = 1500L
        const val FG_CACHE_MS = 5000L
        const val LATENCY_ROWS = 128
        const val MAX_FRAME_GAP_NS = 500_000_000L

        val SPLIT_WS = Regex("\\s+")
        val PACKAGE_RE = Regex("[A-Za-z0-9_.]+")

        val CPU_ZONE_1 = Regex("^cpu-\\d-\\d-usr$")
        val CPU_ZONE_2 = Regex("^cpuss-\\d-usr$")
        val GPU_ZONE = Regex("^gpuss-\\d-usr$")
        val DDR_ZONE = Regex("^ddr-\\d-usr$")
    }
}
