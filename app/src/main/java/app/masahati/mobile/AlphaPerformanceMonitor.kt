package app.masahati.mobile

import android.app.Activity
import androidx.metrics.performance.JankStats
import com.github.anrwatchdog.ANRWatchDog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AlphaPerformanceMonitor(private val activity: Activity) : AutoCloseable {
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "masahati-health-writer").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val file = File(activity.filesDir, "diagnostics/health.log").apply {
        parentFile?.mkdirs()
    }

    private var jankStats: JankStats? = null
    private var anrWatchDog: ANRWatchDog? = null

    fun start() {
        if (closed.get()) return
        if (jankStats == null) {
            jankStats = runCatching {
                JankStats.createAndTrack(activity.window) { frame ->
                    if (!frame.isJank || closed.get()) return@createAndTrack
                    val durationMs = frame.frameDurationUiNanos / 1_000_000.0
                    writeAsync("JANK duration_ms=%.2f".format(java.util.Locale.ROOT, durationMs))
                }
            }.getOrNull()
        }

        if (anrWatchDog == null) {
            anrWatchDog = ANRWatchDog(6_000)
                .setReportMainThreadOnly()
                .setANRListener { error ->
                    val trace = StringWriter().also { sw ->
                        PrintWriter(sw).use { error.printStackTrace(it) }
                    }.toString().take(24_000)
                    writeAsync("ANR\n$trace")
                }
                .also { it.start() }
        }
    }

    fun onResume() {
        jankStats?.isTrackingEnabled = true
    }

    fun onPause() {
        jankStats?.isTrackingEnabled = false
    }

    private fun writeAsync(message: String) {
        if (closed.get()) return
        writer.execute {
            runCatching {
                rotateIfNeeded()
                file.appendText("${Instant.now()} $message\n")
            }
        }
    }

    private fun rotateIfNeeded() {
        if (!file.isFile || file.length() <= MAX_BYTES) return
        val bytes = file.readBytes()
        val keep = bytes.copyOfRange((bytes.size - KEEP_BYTES).coerceAtLeast(0), bytes.size)
        file.writeBytes(keep)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        jankStats?.isTrackingEnabled = false
        jankStats = null
        anrWatchDog?.interrupt()
        anrWatchDog = null
        writer.shutdownNow()
    }

    companion object {
        private const val MAX_BYTES = 256 * 1024L
        private const val KEEP_BYTES = 128 * 1024
    }
}
