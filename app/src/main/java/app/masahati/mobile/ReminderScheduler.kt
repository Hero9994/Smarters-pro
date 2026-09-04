package app.masahati.mobile

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit

object ReminderTime {
    fun nextDaily(now: ZonedDateTime, hour: Int, minute: Int): Long {
        var candidate = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        return candidate.toInstant().toEpochMilli()
    }

    fun nextWeekly(now: ZonedDateTime, dayOfWeek: Int, hour: Int, minute: Int): Long {
        val targetDay = DayOfWeek.of(dayOfWeek.coerceIn(1, 7))
        var candidate = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        var add = (targetDay.value - candidate.dayOfWeek.value + 7) % 7
        if (add == 0 && !candidate.isAfter(now)) add = 7
        candidate = candidate.plusDays(add.toLong())
        return candidate.toInstant().toEpochMilli()
    }

    fun parseWeekday(value: String): Int? {
        val normalized = value.trim().lowercase(Locale.ROOT)
        return when (normalized) {
            "الاثنين", "الإثنين", "اثنين", "الإثنين", "monday", "mon", "montag" -> DayOfWeek.MONDAY.value
            "الثلاثاء", "ثلاثاء", "tuesday", "tue", "dienstag" -> DayOfWeek.TUESDAY.value
            "الأربعاء", "الاربعاء", "أربعاء", "اربعاء", "wednesday", "wed", "mittwoch" -> DayOfWeek.WEDNESDAY.value
            "الخميس", "خميس", "thursday", "thu", "donnerstag" -> DayOfWeek.THURSDAY.value
            "الجمعة", "جمعة", "friday", "fri", "freitag" -> DayOfWeek.FRIDAY.value
            "السبت", "سبت", "saturday", "sat", "samstag" -> DayOfWeek.SATURDAY.value
            "الأحد", "الاحد", "أحد", "احد", "sunday", "sun", "sonntag" -> DayOfWeek.SUNDAY.value
            else -> normalized.toIntOrNull()?.takeIf { it in 1..7 }
        }
    }

    fun parseClock(value: String): Pair<Int, Int>? {
        val m = Regex("(?:^|\\D)([01]?\\d|2[0-3])[:.]([0-5]\\d)(?:$|\\D)").find(value) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }
}

data class ReminderCreationResult(
    val reminderId: Long,
    val description: String,
    val exact: Boolean
)

object ReminderScheduler {
    const val CHANNEL_ID = "masahati_reminders_v1"
    private const val ACTION_FIRE = "app.masahati.mobile.REMINDER_FIRE"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "تذكيرات مساحاتي",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "التذكيرات التي ينشئها مساعد مساحاتي"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun exactAlarmsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun schedule(context: Context, db: MasahatiDatabase, reminder: ReminderRow, fromMillis: Long = System.currentTimeMillis()): Boolean {
        if (!reminder.enabled) return false
        ensureChannel(context)
        val triggerAt = computeNext(reminder, fromMillis) ?: return false
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pending = fireIntent(context, reminder.id)
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
        db.updateReminderNextFire(reminder.id, triggerAt)
        enqueueBackup(context, reminder.id, triggerAt)
        return exact
    }

    fun cancel(context: Context, reminderId: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(fireIntent(context, reminderId))
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(reminderId))
    }

    private fun enqueueBackup(context: Context, reminderId: Long, triggerAt: Long) {
        val delay = (triggerAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val data = Data.Builder().putLong("reminder_id", reminderId).build()
        val request = OneTimeWorkRequestBuilder<ReminderBackupWorker>()
            .setInputData(data)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag("masahati-reminder")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName(reminderId),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun workName(reminderId: Long): String = "masahati-reminder-$reminderId"

    fun rescheduleAll(context: Context) {
        val db = MasahatiDatabase(context.applicationContext)
        try {
            db.listActiveReminders().forEach { schedule(context, db, it) }
        } finally {
            db.close()
        }
    }

    fun createFromAgent(
        context: Context,
        db: MasahatiDatabase,
        spaceId: Long,
        args: JSONObject,
        sourceText: String
    ): ReminderCreationResult? {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val explicitTitle = args.optString("title").trim()
        val title = explicitTitle.ifBlank { "تذكير مساحاتي" }.take(100)
        val body = args.optString("body").trim().ifBlank { sourceText.trim() }.take(500)

        val natural = NaturalReminderParser.parse(sourceText, now)
        if (natural != null) {
            if (!natural.ready) return null
            val next = natural.triggerAt ?: return null
            val id = db.createReminder(
                spaceId = spaceId,
                title = title,
                body = body,
                repeatRule = natural.repeatRule,
                dayOfWeek = natural.dayOfWeek,
                hour = natural.hour,
                minute = natural.minute,
                nextFireAt = next
            )
            val row = db.getReminder(id) ?: return null
            val exact = schedule(context, db, row)
            return ReminderCreationResult(
                id,
                natural.description ?: formatTrigger(next, zone),
                exact
            )
        }

        val relativeMinutes = readRelativeMinutes(args, sourceText)
        if (relativeMinutes != null && relativeMinutes > 0) {
            val next = now.plusMinutes(relativeMinutes.toLong()).withSecond(0).withNano(0).toInstant().toEpochMilli()
            val id = db.createReminder(spaceId, title, body, "none", null, null, null, next)
            val row = db.getReminder(id) ?: return null
            val exact = schedule(context, db, row)
            return ReminderCreationResult(id, "بعد $relativeMinutes دقيقة", exact)
        }

        val triggerAt = parseTriggerAt(args, zone)
        if (triggerAt != null) {
            val id = db.createReminder(spaceId, title, body, "none", null, null, null, triggerAt)
            val row = db.getReminder(id) ?: return null
            val exact = schedule(context, db, row)
            return ReminderCreationResult(id, formatTrigger(triggerAt, zone), exact)
        }

        val timeValue = args.optString("time").ifBlank { sourceText }
        val clock = ReminderTime.parseClock(timeValue) ?: return null
        val dayRaw = args.optString("day_of_week").ifBlank { args.optString("day") }
        val day = ReminderTime.parseWeekday(dayRaw).orElseFromText(sourceText)
        val repeatRaw = args.optString("repeat").lowercase(Locale.ROOT)
        val isDaily = repeatRaw in setOf("daily", "day", "كل يوم", "يومي") || sourceText.contains("كل يوم")
        val isWeekly = repeatRaw in setOf("weekly", "week", "أسبوعي", "اسبوعي", "كل أسبوع", "كل اسبوع") || day != null

        val rule: String
        val next: Long
        val dayForDb: Int?
        when {
            isDaily -> {
                rule = "daily"
                dayForDb = null
                next = ReminderTime.nextDaily(now, clock.first, clock.second)
            }
            isWeekly && day != null -> {
                rule = "weekly"
                dayForDb = day
                next = ReminderTime.nextWeekly(now, day, clock.first, clock.second)
            }
            else -> {
                rule = "none"
                dayForDb = null
                next = ReminderTime.nextDaily(now, clock.first, clock.second)
            }
        }

        val id = db.createReminder(spaceId, title, body, rule, dayForDb, clock.first, clock.second, next)
        val row = db.getReminder(id) ?: return null
        val exact = schedule(context, db, row)
        val description = when (rule) {
            "daily" -> "كل يوم الساعة ${clock.first.toString().padStart(2, '0')}:${clock.second.toString().padStart(2, '0')}"
            "weekly" -> "كل ${weekdayArabic(dayForDb!!)} الساعة ${clock.first.toString().padStart(2, '0')}:${clock.second.toString().padStart(2, '0')}"
            else -> formatTrigger(next, zone)
        }
        return ReminderCreationResult(id, description, exact)
    }

    private fun computeNext(reminder: ReminderRow, fromMillis: Long): Long? {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(fromMillis), zone)
        return when (reminder.repeatRule) {
            "daily" -> {
                val h = reminder.hour ?: return null
                val m = reminder.minute ?: return null
                ReminderTime.nextDaily(now, h, m)
            }
            "weekly" -> {
                val d = reminder.dayOfWeek ?: return null
                val h = reminder.hour ?: return null
                val m = reminder.minute ?: return null
                ReminderTime.nextWeekly(now, d, h, m)
            }
            else -> reminder.nextFireAt?.let { scheduled ->
                when {
                    scheduled > fromMillis + 500L -> scheduled
                    scheduled >= fromMillis - 6L * 60L * 60L * 1000L -> fromMillis + 1_000L
                    else -> null
                }
            }
        }
    }

    private fun fireIntent(context: Context, reminderId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra("reminder_id", reminderId)
        }
        return PendingIntent.getBroadcast(
            context,
            (reminderId and 0x7fffffff).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun readRelativeMinutes(args: JSONObject, text: String): Int? {
        val direct = args.optInt("delay_minutes", 0)
        if (direct > 0) return direct.coerceAtMost(60 * 24 * 30)
        val min = Regex("بعد\\s+(\\d{1,5})\\s*(?:دقيقة|دقائق|دقايق|دقيقه|minute|minutes)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (min != null) return min
        val hours = Regex("بعد\\s+(\\d{1,4})\\s*(?:ساعة|ساعات|ساعه|hour|hours)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
        return hours?.times(60)
    }

    private fun parseTriggerAt(args: JSONObject, zone: ZoneId): Long? {
        val raw = args.optString("trigger_at").trim().ifBlank { args.optString("datetime").trim() }
        if (raw.isBlank()) return null
        return try {
            ZonedDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(raw).atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private fun formatTrigger(epoch: Long, zone: ZoneId): String {
        val dt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epoch), zone)
        return dt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY))
    }

    private fun weekdayArabic(day: Int): String = when (day) {
        1 -> "الاثنين"
        2 -> "الثلاثاء"
        3 -> "الأربعاء"
        4 -> "الخميس"
        5 -> "الجمعة"
        6 -> "السبت"
        else -> "الأحد"
    }

    private fun Int?.orElseFromText(text: String): Int? {
        if (this != null) return this
        val candidates = listOf("الاثنين", "الإثنين", "اثنين", "الثلاثاء", "ثلاثاء", "الأربعاء", "الاربعاء", "أربعاء", "اربعاء", "الخميس", "خميس", "الجمعة", "جمعة", "السبت", "سبت", "الأحد", "الاحد", "أحد", "احد", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonntag")
        return candidates.firstNotNullOfOrNull { token -> if (text.contains(token, ignoreCase = true)) ReminderTime.parseWeekday(token) else null }
    }
}

object ReminderDeliveryText {
    fun build(reminder: ReminderRow, zone: ZoneId = ZoneId.systemDefault(), nowMillis: Long = System.currentTimeMillis()): String {
        val fireAt = reminder.nextFireAt ?: nowMillis
        val fireTime = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(fireAt), zone)
        val today = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone).toLocalDate()
        val dayLabel = when (fireTime.toLocalDate()) {
            today -> "اليوم"
            today.plusDays(1) -> "غداً"
            else -> fireTime.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY))
        }
        val timeLabel = fireTime.format(DateTimeFormatter.ofPattern("HH.mm", Locale.GERMANY))
        val task = cleanTask(reminder.body)
        return if (task.isBlank()) {
            "تذكير: $dayLabel الساعة $timeLabel"
        } else {
            "تذكير: $dayLabel الساعة $timeLabel $task"
        }
    }

    fun cleanTask(source: String): String {
        var value = source.trim()
        value = value.replace(
            Regex("^(?:لو سمحت\\s*)?(?:ذكرني|ذكّرني|اعمل(?:ي)?\\s+تذكير|سوي(?:لي)?\\s+تذكير|تذكير|remind me|erinnere mich)\\s*", RegexOption.IGNORE_CASE),
            ""
        )
        value = value.replace(
            Regex("(?:الساعة|الساعه|um|at)\\s*[0-2]?\\d(?:[:.]\\d{2})?\\s*(?:صباحاً|صباحا|صباح|مساءً|مساء|مسا|pm|am)?", RegexOption.IGNORE_CASE),
            " "
        )
        value = value.replace(
            Regex("\\b(?:[01]?\\d|2[0-3])[:.]\\d{2}\\b"),
            " "
        )
        value = value.replace(
            Regex("(?:اليوم|بكرا|غداً|غدا|tomorrow|today|heute|morgen)", RegexOption.IGNORE_CASE),
            " "
        )
        value = value.replace(
            Regex("(?:كل\\s+)?(?:يوم\\s+)?(?:الاثنين|الإثنين|اثنين|الثلاثاء|ثلاثاء|الأربعاء|الاربعاء|أربعاء|اربعاء|الخميس|خميس|الجمعة|جمعة|السبت|سبت|الأحد|الاحد|أحد|احد|monday|tuesday|wednesday|thursday|friday|saturday|sunday|montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)", RegexOption.IGNORE_CASE),
            " "
        )
        value = value.replace(
            Regex("(?:كل\\s+يوم|يومياً|يوميا|كل\\s+أسبوع|كل\\s+اسبوع|أسبوعياً|اسبوعيا|daily|weekly|every day|every week|jeden tag|wöchentlich|woechentlich)", RegexOption.IGNORE_CASE),
            " "
        )
        value = value.replace(
            Regex("(?:^|\\s)\\d{1,2}[./-]\\d{1,2}(?:[./-]\\d{2,4})?(?:\\s|$)"),
            " "
        )
        value = value.replace(Regex("\\s+"), " ").trim(' ', '-', '،', ',')
        return value
    }
}

object ReminderDelivery {
    fun deliver(context: Context, reminderId: Long) {
        if (reminderId <= 0L) return
        val appContext = context.applicationContext
        val db = MasahatiDatabase(appContext)
        try {
            val reminder = db.getReminder(reminderId) ?: return
            if (!reminder.enabled) return

            val now = System.currentTimeMillis()
            val scheduledAt = reminder.nextFireAt ?: now
            val alreadyDeliveredForThisFire = reminder.deliveredAt?.let { delivered ->
                delivered >= scheduledAt - 60_000L
            } == true
            if (alreadyDeliveredForThisFire) return

            // Mark first so AlarmManager and WorkManager cannot both create duplicate messages.
            db.markReminderDelivered(reminderId, now)

            val deliveredText = ReminderDeliveryText.build(reminder, nowMillis = now)
            db.insertText(reminder.spaceId, "assistant", deliveredText)

            ReminderScheduler.ensureChannel(appContext)
            if (ReminderScheduler.notificationsAllowed(appContext)) {
                val openIntent = Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("open_space_id", reminder.spaceId)
                }
                val contentIntent = PendingIntent.getActivity(
                    appContext,
                    (reminderId and 0x7fffffff).toInt(),
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notification = NotificationCompat.Builder(appContext, ReminderScheduler.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("تذكير مساحاتي")
                    .setContentText(deliveredText)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(deliveredText))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setWhen(scheduledAt)
                    .setContentIntent(contentIntent)
                    .build()
                if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        NotificationManagerCompat.from(appContext).notify((reminderId and 0x7fffffff).toInt(), notification)
                    } catch (_: SecurityException) {
                        // Permission can be revoked between check and notify.
                    }
                }
            }

            if (reminder.repeatRule == "none") {
                db.disableReminder(reminderId)
            } else {
                ReminderScheduler.schedule(appContext, db, reminder, now + 60_000L)
            }
        } finally {
            db.close()
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderDelivery.deliver(context, intent.getLongExtra("reminder_id", -1L))
    }
}

class ReminderBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        ReminderDelivery.deliver(applicationContext, inputData.getLong("reminder_id", -1L))
        return Result.success()
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                ReminderScheduler.rescheduleAll(context.applicationContext)
        }
    }
}
