package app.masahati.mobile

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object MorningBriefBuilder {
    fun build(db: MasahatiDatabase, now: ZonedDateTime = ZonedDateTime.now()): String? {
        val lines = mutableListOf<String>()
        val nowMs = now.toInstant().toEpochMilli()
        val endOfTomorrow = now.plusDays(2).toInstant().toEpochMilli()

        val actions = db.listOpenActionItems(100)
            .filter { it.dueAt == null || it.dueAt <= endOfTomorrow }
            .take(4)
        actions.forEach { action ->
            lines += "⚑ " + action.title
        }

        db.listActiveReminders()
            .filter { it.nextFireAt != null && it.nextFireAt in nowMs..endOfTomorrow }
            .take(4)
            .forEach { reminder ->
                lines += "⏰ " + ReminderDeliveryText.cleanTask(reminder.body).ifBlank { reminder.title }
            }

        AlphaDateTracker.upcoming(db, now.toLocalDate(), horizonDays = 30, includeOverdueDays = 365)
            .take(4)
            .forEach { notice ->
                val prefix = if (notice.kind == "due") "📅" else "⏳"
                lines += "$prefix " + AlphaDateTracker.label(notice)
            }

        if (lines.isEmpty()) return null
        return buildString {
            append("مساحاتي اليوم")
            lines.take(8).forEach { append("\n").append(it) }
        }
    }
}

object MorningBriefScheduler {
    private const val PREFS = "masahati_alpha_preferences"
    private const val KEY_ENABLED = "morning_brief_enabled"
    private const val UNIQUE_WORK = "masahati-alpha-morning-brief"
    const val HOUR = 8

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) schedule(context) else WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }

    fun ensureIfEnabled(context: Context) {
        if (isEnabled(context)) schedule(context)
    }

    private fun schedule(context: Context) {
        val now = ZonedDateTime.now()
        var next = now.withHour(HOUR).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis().coerceAtLeast(0L)
        val request = PeriodicWorkRequestBuilder<MorningBriefWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(UNIQUE_WORK)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

class MorningBriefWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        if (!MorningBriefScheduler.isEnabled(applicationContext)) return Result.success()
        if (!ReminderScheduler.notificationsAllowed(applicationContext)) return Result.success()

        val db = MasahatiDatabase(applicationContext)
        val text = try {
            MorningBriefBuilder.build(db) ?: return Result.success()
        } finally {
            db.close()
        }

        ReminderScheduler.ensureChannel(applicationContext)
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            90217,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("ملخص مساحاتي الصباحي")
            .setContentText(text.lineSequence().drop(1).firstOrNull() ?: "لديك أشياء تحتاج انتباهك")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .build()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }
        try {
            NotificationManagerCompat.from(applicationContext).notify(90217, notification)
        } catch (_: SecurityException) {
            return Result.success()
        }
        return Result.success()
    }
}
