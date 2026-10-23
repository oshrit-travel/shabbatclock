package com.shabbatclock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-registers the alarms after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.reschedule(context)
    }
}
