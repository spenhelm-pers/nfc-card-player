package com.spencer.tagtunes

import android.content.Context
import android.media.AudioManager

/** Media volume helpers that respect the user's volume limit. */
class VolumeLimiter(context: Context, private val store: TagStore) {
    private val audio = context.getSystemService(AudioManager::class.java)

    val max: Int get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val current: Int get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

    /** Highest volume step allowed by the limit (the phone's max when there is no limit). */
    fun allowed(): Int = ((max * store.volumeCap + 99) / 100).coerceIn(1, max)

    fun atLimit(): Boolean = store.volumeCap < 100 && current >= allowed()

    /** Pull the volume back down if anything pushed it over the limit. */
    fun enforce() {
        if (store.volumeCap >= 100) return
        val limit = allowed()
        if (current > limit) audio.setStreamVolume(AudioManager.STREAM_MUSIC, limit, 0)
    }

    fun step(direction: Int) {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (current + direction).coerceIn(0, allowed()), 0)
    }
}
