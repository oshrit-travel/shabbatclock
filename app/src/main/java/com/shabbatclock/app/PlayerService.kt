package com.shabbatclock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.io.FileInputStream

/**
 * Foreground service that actually makes the sound. Started by an alarm (screen may be off) or by the page (test mode).
 * Kinds: song | siren | chime | refresh | stop
 */
class PlayerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var siren: AudioTrack? = null
    private var chimeTrack: AudioTrack? = null
    private var wake: PowerManager.WakeLock? = null
    private var chimeEndsAt = 0L
    private var holdUntil = 0L
    private var capRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kind = intent?.getStringExtra("kind") ?: "stop"
        if (kind == "stop") {
            stopAllSound()
            stopForegroundCompat()
            // stopSelf(startId) only stops if no newer start request is waiting - otherwise a "song" start that
            // arrives right after a "stop" would be dropped and Android kills the app for not calling startForeground.
            stopSelf(startId)
            return START_NOT_STICKY
        }

        goForeground(
            intent?.getStringExtra("title").orEmpty().ifEmpty { "שעון שבת" },
            intent?.getStringExtra("body").orEmpty()
        )
        acquireWake()

        val slot = intent?.getStringExtra("slot").orEmpty()
        val dur = intent?.getIntExtra("dur", 15) ?: 15
        val cap = intent?.getIntExtra("cap", 0) ?: 0

        when (kind) {
            "song" -> {
                stopAllSound()
                val f = AudioStore.fileIfExists(this, slot)
                if (f != null) playFile(f, 0, true)
            }
            "siren" -> {
                stopAllSound()
                val f = AudioStore.fileIfExists(this, slot)
                if (f != null) playFile(f, cap, false) else playSiren(dur)
            }
            "chime" -> playChime()
            "refresh" -> holdUntil = System.currentTimeMillis() + 3 * 60_000L
        }
        scheduleFinishCheck()
        return START_NOT_STICKY
    }

    // ---------- playback ----------
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun playFile(file: File, capSec: Int, fade: Boolean) {
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            FileInputStream(file).use { mp.setDataSource(it.fd) }
            mp.setOnCompletionListener { stopMedia(); scheduleFinishCheck() }
            mp.setOnErrorListener { _, _, _ -> stopMedia(); scheduleFinishCheck(); true }
            mp.prepare()
            mp.setVolume(if (fade) 0f else 1f, if (fade) 0f else 1f)
            mp.start()
            player = mp
            if (fade) fadeIn(mp, 2500)
            if (capSec > 0) {
                val r = Runnable { stopMedia(); scheduleFinishCheck() }
                capRunnable = r
                handler.postDelayed(r, capSec * 1000L)
            }
        } catch (e: Throwable) {
            stopMedia()
        }
    }

    private fun fadeIn(mp: MediaPlayer, ms: Int) {
        val steps = 20
        for (i in 1..steps) {
            handler.postDelayed({
                try { if (player === mp) mp.setVolume(i / steps.toFloat(), i / steps.toFloat()) } catch (_: Exception) {}
            }, (ms / steps * i).toLong())
        }
    }

    private fun stopMedia() {
        capRunnable?.let { handler.removeCallbacks(it) }
        capRunnable = null
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    private fun makeTrack(data: ShortArray): AudioTrack {
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(Synth.RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(data, 0, data.size)
        return t
    }

    private fun playSiren(sec: Int) {
        try {
            val t = makeTrack(Synth.siren(sec))
            siren = t
            t.play()
            handler.postDelayed({
                if (siren === t) { releaseTrack(t); siren = null; scheduleFinishCheck() }
            }, sec * 1000L + 400)
        } catch (_: Exception) {}
    }

    private fun playChime() {
        try {
            // duck whatever is playing to 25% while the chime rings, then restore
            player?.let { try { it.setVolume(0.25f, 0.25f) } catch (_: Exception) {} }
            val data = Synth.chime()
            val t = makeTrack(data)
            chimeTrack = t
            t.play()
            val ms = data.size * 1000L / Synth.RATE
            chimeEndsAt = System.currentTimeMillis() + ms + 300
            handler.postDelayed({
                if (chimeTrack === t) { releaseTrack(t); chimeTrack = null }
                player?.let { try { it.setVolume(1f, 1f) } catch (_: Exception) {} }
                scheduleFinishCheck()
            }, ms + 300)
        } catch (_: Exception) {}
    }

    private fun releaseTrack(t: AudioTrack) {
        try { t.stop() } catch (_: Exception) {}
        try { t.release() } catch (_: Exception) {}
    }

    private fun stopAllSound() {
        stopMedia()
        siren?.let { releaseTrack(it) }; siren = null
        chimeTrack?.let { releaseTrack(it) }; chimeTrack = null
    }

    // ---------- lifecycle ----------
    private fun scheduleFinishCheck() {
        handler.postDelayed({ finishIfIdle() }, 700)
    }

    private fun finishIfIdle() {
        val busy = player != null || siren != null || chimeTrack != null ||
            System.currentTimeMillis() < holdUntil
        if (busy) { handler.postDelayed({ finishIfIdle() }, 1500); return }
        releaseWake()
        stopForegroundCompat()
        stopSelf()
    }

    private fun acquireWake() {
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shabbatclock:player")
        }
        try { if (wake?.isHeld == false) wake?.acquire(15 * 60_000L) } catch (_: Exception) {}
    }

    private fun releaseWake() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {}
    }

    private fun goForeground(title: String, body: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "אירועי שבת", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(null, null)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            .setFullScreenIntent(open, true) // wakes the screen and opens the clock
        val n = b.build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Throwable) {
            try { startForeground(NOTIF_ID, n) } catch (_: Throwable) {}
        }
    }

    override fun onDestroy() {
        stopAllSound()
        releaseWake()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "shabbat_events"
        const val NOTIF_ID = 4001
    }
}
