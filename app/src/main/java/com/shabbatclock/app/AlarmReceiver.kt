package com.shabbatclock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = Intent(context, PlayerService::class.java)
        intent.extras?.let { svc.putExtras(it) }
        ContextCompat.startForegroundService(context, svc)
    }
}
