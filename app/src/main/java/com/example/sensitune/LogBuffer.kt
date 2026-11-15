package com.example.sensitune

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory log ring buffer (2000 entries), mirroring the native ring buffer.
 * Used by the UserService process (service logs) and by the UI process.
 */
object LogBuffer {

    const val LEVEL_DEBUG = 0
    const val LEVEL_INFO = 1
    const val LEVEL_WARN = 2
    const val LEVEL_ERROR = 3

    /** Kotlin entries use this offset so seq stays monotonic across native+Kotlin logs. */
    const val SEQ_OFFSET = 1_000_000L

    const val CAPACITY = 2000

    data class Entry(
        val seq: Long,
        val ts: Long,
        val level: Int,
        val thread: String,
        val msg: String
    )

    private val ring = arrayOfNulls<Entry>(CAPACITY)
    private var nextSeq = 1L
    private var count = 0
    private val lock = Any()

    fun debug(tag: String, msg: String) = add(LEVEL_DEBUG, tag, msg)
    fun info(tag: String, msg: String) = add(LEVEL_INFO, tag, msg)
    fun warn(tag: String, msg: String) = add(LEVEL_WARN, tag, msg)
    fun error(tag: String, msg: String, t: Throwable? = null) {
        val detail = if (t == null) {
            msg
        } else {
            val frames = t.stackTrace.take(8).joinToString("\n") { "    at $it" }
            "$msg: ${t.javaClass.simpleName}: ${t.message}\n$frames"
        }
        add(LEVEL_ERROR, tag, detail)
    }

    private fun add(level: Int, tag: String, msg: String) {
        val now = System.currentTimeMillis()
        val thread = Thread.currentThread().name
        synchronized(lock) {
            val e = Entry(SEQ_OFFSET + nextSeq, now, level, "$tag/$thread", msg)
            nextSeq++
            ring[((nextSeq - 2) % CAPACITY).toInt()] = e
            if (count < CAPACITY) count++
        }
        // Also mirror into logcat for adb-based debugging.
        when (level) {
            LEVEL_DEBUG -> android.util.Log.d("SensiTune", "[$tag] $msg")
            LEVEL_INFO -> android.util.Log.i("SensiTune", "[$tag] $msg")
            LEVEL_WARN -> android.util.Log.w("SensiTune", "[$tag] $msg")
            else -> android.util.Log.e("SensiTune", "[$tag] $msg")
        }
    }

    fun snapshot(sinceSeq: Long = 0L): List<Entry> = synchronized(lock) {
        val out = ArrayList<Entry>(count)
        val start = nextSeq - count
        for (i in start until nextSeq) {
            val e = ring[((i - 1) % CAPACITY).toInt()] ?: continue
            if (e.seq > sinceSeq) out.add(e)
        }
        out.sortBy { it.seq }
        out
    }

    fun clear() = synchronized(lock) {
        ring.fill(null)
        count = 0
    }

    fun levelName(level: Int): String = when (level) {
        LEVEL_DEBUG -> "DEBUG"
        LEVEL_WARN -> "WARN"
        LEVEL_ERROR -> "ERROR"
        else -> "INFO"
    }

    fun format(e: Entry): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(e.ts))
        return "$time ${levelName(e.level)} [${e.thread}] ${e.msg}"
    }

    fun allText(): String = snapshot().joinToString("\n") { format(it) }
}
