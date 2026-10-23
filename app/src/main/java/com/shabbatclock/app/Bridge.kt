package com.shabbatclock.app

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.ContextCompat

/** Exposed to the web page as window.AndroidBridge. */
class Bridge(private val ctx: Context) {

    @JavascriptInterface
    fun setPlan(json: String) { Scheduler.applyPlan(ctx, json) }

    @JavascriptInterface
    fun hasAudio(slot: String, name: String, size: Double): Boolean =
        AudioStore.has(ctx, slot, name, size.toLong())

    @JavascriptInterface
    fun audioChunk(slot: String, index: Int, b64: String, last: Boolean, name: String, size: Double): Boolean {
        val bytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (e: Exception) { return false }
        if (!AudioStore.appendChunk(ctx, slot, index, bytes)) return false
        return if (last) AudioStore.commit(ctx, slot, name, size.toLong()) else true
    }

    @JavascriptInterface
    fun playSlot(slot: String, cap: Int) = start("song", slot, 0, cap, "שיר", "")

    @JavascriptInterface
    fun playSiren(slot: String, dur: Int, cap: Int) = start("siren", slot, dur, cap, "צפירה", "")

    @JavascriptInterface
    fun chimeNow() = start("chime", "", 0, 0, "תזכורת", "")

    @JavascriptInterface
    fun stopAll() {
        try { ctx.startService(Intent(ctx, PlayerService::class.java).putExtra("kind", "stop")) } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun toast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun start(kind: String, slot: String, dur: Int, cap: Int, title: String, body: String) {
        val i = Intent(ctx, PlayerService::class.java)
            .putExtra("kind", kind).putExtra("slot", slot)
            .putExtra("dur", if (dur > 0) dur else 15).putExtra("cap", cap)
            .putExtra("title", title).putExtra("body", body)
        try { ContextCompat.startForegroundService(ctx, i) } catch (_: Exception) {}
    }
}
