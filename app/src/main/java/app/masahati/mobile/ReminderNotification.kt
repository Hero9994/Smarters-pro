package app.masahati.mobile

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ReminderNotification {
    private const val ACTION_SNOOZE = "app.masahati.mobile.REMINDER_SNOOZE"
    private const val ACTION_FIRE = "app.masahati.mobile.REMINDER_FIRE"

    fun show(context: Context, reminder: ReminderRow, spaceTitle: String) {
        val id = reminder.id
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_space_id", reminder.spaceId)
        }
        val openPending = PendingIntent.getActivity(
            context,
            (id and 0x7fffffff).toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozeIntent = Intent(context, ReminderSnoozeReceiver::class.java).apply {
            action = ACTION_SNOOZE
            putExtra("reminder_id", id)
        }
        val snoozePending = PendingIntent.getBroadcast(
            context,
            ((id + 100000L) and 0x7fffffff).toInt(),
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val trigger = reminder.nextFireAt ?: System.currentTimeMillis()
        val dateTime = DateTimeFormatter.ofPattern("dd.MM.yyyy • HH:mm", Locale.GERMANY)
            .format(Instant.ofEpochMilli(trigger).atZone(ZoneId.systemDefault()))
        val reason = reminder.title.ifBlank { "تذكير" }
        val detail = buildString {
            append(reason)
            if (reminder.body.isNotBlank() && reminder.body != reason) {
                append("\n\nالسبب: ").append(reminder.body)
            }
            append("\n\nالموعد: ").append(dateTime)
            append("\nالمحادثة: ").append(spaceTitle)
        }

        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("تذكير من «$spaceTitle»")
            .setContentText("$reason • $dateTime")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setSubText("مساحاتي • $dateTime")
            .setWhen(trigger)
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFF366F6B.toInt())
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_view, "فتح المحادثة", openPending)
            .addAction(android.R.drawable.ic_popup_sync, "تأجيل 10 دقائق", snoozePending)
            .build()

        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            try {
                NotificationManagerCompat.from(context).notify((id and 0x7fffffff).toInt(), notification)
            } catch (_: SecurityException) { }
        }
    }

    fun scheduleSnooze(context: Context, reminderId: Long, minutes: Int = 10) {
        val appContext = context.applicationContext
        val db = MasahatiDatabase(appContext)
        try {
            val reminder = db.getReminder(reminderId) ?: return
            val next = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
            db.snoozeReminder(reminderId, next)
            val alarmManager = appContext.getSystemService(AlarmManager::class.java)
            val fireIntent = Intent(appContext, ReminderReceiver::class.java).apply {
                action = ACTION_FIRE
                putExtra("reminder_id", reminder.id)
            }
            val pending = PendingIntent.getBroadcast(
                appContext,
                (reminder.id and 0x7fffffff).toInt(),
                fireIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true
            if (exact) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            NotificationManagerCompat.from(appContext).cancel((reminder.id and 0x7fffffff).toInt())
        } finally {
            db.close()
        }
    }
}

class ReminderSnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("reminder_id", -1L)
        if (id > 0L) ReminderNotification.scheduleSnooze(context, id, 10)
    }
}
