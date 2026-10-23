package com.shabbatclock.app

import kotlin.math.PI
import kotlin.math.sin

/** Built-in sounds, identical in character to the web version: flat 640Hz siren and a ding-dong chime. */
object Synth {
    const val RATE = 44100

    fun siren(seconds: Int): ShortArray {
        val n = seconds * RATE
        val fade = (0.25 * RATE).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val env = when {
                i < fade -> i / fade.toDouble()
                i > n - fade -> (n - i) / fade.toDouble()
                else -> 1.0
            }
            out[i] = (sin(2 * PI * 640.0 * i / RATE) * 0.32 * env * 32767).toInt().toShort()
        }
        return out
    }

    /** 3 x (880Hz, 660Hz) every 0.7s. */
    fun chime(): ShortArray {
        val total = (3 * 0.7 * RATE).toInt()
        val out = ShortArray(total)
        for (r in 0 until 3) {
            for ((j, freq) in listOf(880.0, 660.0).withIndex()) {
                val start = ((r * 0.7 + j * 0.32) * RATE).toInt()
                val len = (0.32 * RATE).toInt()
                val up = (0.05 * RATE).toInt()
                for (k in 0 until len) {
                    val idx = start + k
                    if (idx >= total) break
                    val env = if (k < up) k / up.toDouble() else 1.0 - (k - up) / (len - up).toDouble()
                    val s = (sin(2 * PI * freq * k / RATE) * 0.3 * env * 32767).toInt()
                    out[idx] = (out[idx] + s).coerceIn(-32768, 32767).toShort()
                }
            }
        }
        return out
    }
}
