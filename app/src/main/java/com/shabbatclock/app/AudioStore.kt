package com.shabbatclock.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/** Keeps the user's uploaded audio files in app-private storage so the native layer can play them with the screen off. */
object AudioStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("audio_meta", Context.MODE_PRIVATE)
    private fun safe(slot: String) = slot.replace(Regex("[^a-zA-Z0-9_]"), "_")
    private fun dir(ctx: Context) = File(ctx.filesDir, "audio").apply { mkdirs() }

    fun file(ctx: Context, slot: String) = File(dir(ctx), safe(slot) + ".bin")
    private fun tmp(ctx: Context, slot: String) = File(dir(ctx), safe(slot) + ".tmp")

    fun fileIfExists(ctx: Context, slot: String): File? {
        if (slot.isEmpty()) return null
        val f = file(ctx, slot)
        return if (f.exists() && f.length() > 0) f else null
    }

    fun has(ctx: Context, slot: String, name: String, size: Long): Boolean {
        val p = prefs(ctx)
        val f = file(ctx, slot)
        return f.exists() && f.length() == size &&
            p.getString(safe(slot) + "_name", null) == name &&
            p.getLong(safe(slot) + "_size", -1) == size
    }

    fun appendChunk(ctx: Context, slot: String, index: Int, bytes: ByteArray): Boolean {
        return try {
            FileOutputStream(tmp(ctx, slot), index != 0).use { it.write(bytes) }
            true
        } catch (e: Exception) { false }
    }

    fun commit(ctx: Context, slot: String, name: String, size: Long): Boolean {
        val t = tmp(ctx, slot)
        if (!t.exists() || t.length() != size) { t.delete(); return false }
        val dest = file(ctx, slot)
        dest.delete()
        if (!t.renameTo(dest)) return false
        prefs(ctx).edit().putString(safe(slot) + "_name", name).putLong(safe(slot) + "_size", size).apply()
        return true
    }
}
