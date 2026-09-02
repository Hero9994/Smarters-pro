package app.masahati.mobile

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MasahatiFormat {
    fun safeFileName(input: String): String {
        val cleaned=input.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").trim().take(120)
        return cleaned.ifBlank { "file" }
    }

    fun shortTime(timestamp: Long, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat("HH:mm",locale).format(Date(timestamp))

    fun listDate(timestamp: Long, now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
        val day=SimpleDateFormat("yyyyMMdd",locale)
        return if(day.format(Date(timestamp))==day.format(Date(now))) shortTime(timestamp,locale)
        else SimpleDateFormat("dd.MM",locale).format(Date(timestamp))
    }
}
