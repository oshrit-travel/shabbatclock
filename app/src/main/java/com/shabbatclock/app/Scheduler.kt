package com.shabbatclock.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray

/**
 * Receives the plan (list of future events) from the web page, stores it, and registers one system alarm per event.
 * Alarms use setAlarmClock(): the system treats them like a real alarm clock - they fire in Doze and wake the device.
 */
object Scheduler {
    private const val REFRESH_CODE = 9000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("plan", Context.MODE_PRIVATE)

    private fun alarmIntent(ctx: Context, code: Int, extras: Intent?): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
        if (extras != null) i.putExtras(extras)
        return PendingIntent.getBroadcast(
            ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun applyPlan(ctx: Context, planJson: String) {
        prefs(ctx).edit().putString("plan", planJson).apply()
        reschedule(ctx)
    }

    fun reschedule(ctx: Context) {
        val p = prefs(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // cancel everything previously registered
        val old = p.getInt("count", 0)
        for (i in 0 until old) am.cancel(alarmIntent(ctx, i, null))
        am.cancel(alarmIntent(ctx, REFRESH_CODE, null))

        val raw = p.getString("plan", null) ?: return
        val arr = try { JSONArray(raw) } catch (e: Exception) { return }
        val now = System.currentTimeMillis()
        var lastT = 0L

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = o.optLong("t", 0)
            if (t <= now) continue
            if (t > lastT) lastT = t
            val extras = Intent()
                .putExtra("kind", o.optString("kind"))
                .putExtra("slot", o.optString("slot"))
                .putExtra("dur", o.optInt("dur", 15))
                .putExtra("cap", o.optInt("cap", 0))
                .putExtra("title", o.optString("title"))
                .putExtra("body", o.optString("body"))
            am.setAlarmClock(AlarmManager.AlarmClockInfo(t, showIntent(ctx)), alarmIntent(ctx, i, extras))
        }
        p.edit().putInt("count", arr.length()).apply()

        // A "refresh" wake-up: after the last event (or in 6h if none) the screen is woken so the page can fetch next week's times.
        val refreshAt = if (lastT > 0) lastT + 30 * 60_000L else now + 6 * 3_600_000L
        val rx = Intent()
            .putExtra("kind", "refresh")
            .putExtra("title", "מעדכן זמני שבת")
            .putExtra("body", "טוען את הזמנים לשבוע הבא")
        am.setAlarmClock(AlarmManager.AlarmClockInfo(refreshAt, showIntent(ctx)), alarmIntent(ctx, REFRESH_CODE, rx))
    }
}
