package com.metrolist.music.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

data class StatsLogEntry(val time: Long, val level: Int, val tag: String?, val message: String)

/**
 * An in-app copy of everything Timber is told, so a field run can be read without a cable.
 *
 * This fork exists to measure something that is otherwise invisible. Metrolist already logs its
 * whole playback error ladder — the 403/410 branch, the client rollover, the retry limit — but it
 * logs to Logcat, where a person holding a phone never looks. Nothing here changes what is logged
 * or how playback behaves; it only keeps a copy somewhere reachable.
 */
object StatsLog {
    private const val MAX_ENTRIES = 5000

    /** Identical lines inside one frame add nothing and still cost a copy. */
    private const val DUPLICATE_WINDOW_MS = 250L

    /** Rebuilding a 5000 element list per line costs more than the work being logged. */
    private const val PUBLISH_INTERVAL_MS = 1_000L

    private val buffer = ArrayDeque<StatsLogEntry>()
    private val lock = Any()

    private val _logs = MutableStateFlow<List<StatsLogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    @Volatile
    private var enabled = false

    @Volatile
    private var lastPublishedAtMs = 0L

    private var lastLevel = Int.MIN_VALUE
    private var lastTag: String? = null
    private var lastMessage: String? = null
    private var lastAtMs = 0L

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    val isEnabled: Boolean get() = enabled

    internal fun setEnabled(value: Boolean) = synchronized(lock) {
        enabled = value
        if (!value) clearLocked()
    }

    fun append(level: Int, tag: String?, message: String) {
        if (!enabled) return
        val now = System.currentTimeMillis()

        synchronized(lock) {
            if (!enabled) return
            val burstDuplicate =
                level == lastLevel &&
                    tag == lastTag &&
                    message == lastMessage &&
                    now - lastAtMs in 0..DUPLICATE_WINDOW_MS
            if (burstDuplicate) return

            lastLevel = level
            lastTag = tag
            lastMessage = message
            lastAtMs = now

            buffer.addLast(StatsLogEntry(now, level, tag, message))
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()

            val observed = _logs.subscriptionCount.value > 0
            if (observed && now - lastPublishedAtMs >= PUBLISH_INTERVAL_MS) {
                lastPublishedAtMs = now
                _logs.value = buffer.toList()
            }
        }
    }

    /** Everything held right now; sharing must never miss the newest lines. */
    fun snapshot(): List<StatsLogEntry> =
        synchronized(lock) { if (enabled) buffer.toList() else emptyList() }

    /** Publish at once, for a viewer that just opened onto a stale snapshot. */
    fun refresh() = synchronized(lock) {
        lastPublishedAtMs = System.currentTimeMillis()
        _logs.value = if (enabled) buffer.toList() else emptyList()
    }

    fun clear() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        buffer.clear()
        lastPublishedAtMs = 0L
        lastLevel = Int.MIN_VALUE
        lastTag = null
        lastMessage = null
        lastAtMs = 0L
        _logs.value = emptyList()
    }

    fun format(entry: StatsLogEntry): String {
        val stamp = synchronized(timeFormat) { timeFormat.format(Date(entry.time)) }
        val level = when (entry.level) {
            android.util.Log.VERBOSE -> "V"
            android.util.Log.DEBUG -> "D"
            android.util.Log.INFO -> "I"
            android.util.Log.WARN -> "W"
            android.util.Log.ERROR -> "E"
            else -> "?"
        }
        return "[$stamp] $level/${entry.tag ?: ""}: ${entry.message}"
    }
}

/** Forwards Timber into [StatsLog]. */
class StatsLogTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!StatsLog.isEnabled) return
        try {
            StatsLog.append(priority, tag, if (t != null) "$message\n$t" else message)
        } catch (_: Exception) {
            // Logging must never crash the process or recurse into itself.
        }
    }
}

/** Plants and uproots the capture tree, so nothing is held while capture is off. */
object StatsLogController {
    private val tree = StatsLogTree()

    @Volatile
    private var enabled = false

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        if (value) {
            StatsLog.setEnabled(true)
            Timber.plant(tree)
        } else {
            Timber.uproot(tree)
            StatsLog.setEnabled(false)
        }
        enabled = value
    }
}
