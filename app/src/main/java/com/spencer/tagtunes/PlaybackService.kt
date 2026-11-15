package com.spencer.tagtunes

import android.app.PendingIntent
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the player so audio keeps going with the screen off or in other apps.
 * Media3 supplies the notification, lock-screen and Bluetooth controls,
 * audio focus (pauses for calls) and pausing when a speaker disconnects.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var store: TagStore
    private lateinit var volume: VolumeLimiter
    private val handler = Handler(Looper.getMainLooper())

    /** Save progress every few seconds while playing, so a crash or dead battery loses very little. */
    private val saver = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 5000)
        }
    }

    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = volume.enforce()
    }

    override fun onCreate() {
        super.onCreate()
        store = TagStore(this)
        volume = VolumeLimiter(this, store)

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(saver)
                if (isPlaying) handler.postDelayed(saver, 5000) else saveProgress()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                ) {
                    store.nowPlaying?.let { store.setProgress(it, player.currentMediaItemIndex, 0) }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    // End of the playlist: rewind to the first song and wait for Play.
                    player.pause()
                    player.seekTo(0, 0)
                    store.nowPlaying?.let { store.setProgress(it, 0, 0) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Skip a song that won't play instead of stopping the whole playlist.
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                }
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away keeps music going if it's playing; otherwise shut down. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        saveProgress()
        handler.removeCallbacks(saver)
        contentResolver.unregisterContentObserver(volumeObserver)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun saveProgress() {
        val p = session?.player ?: return
        val uid = store.nowPlaying ?: return
        if (p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) return
        store.setProgress(uid, p.currentMediaItemIndex, p.currentPosition.toInt())
    }
}
