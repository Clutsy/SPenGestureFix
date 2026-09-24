package com.spengesturefix

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit

/**
 * Custom wheel sounds, ported from SpenCommand's "Sound setting" option:
 * the user picks any audio file via SAF for the wheel opening and closing
 * moments. Audio is copied into app storage so it survives across reboots
 * without holding content-provider permissions.
 *
 * Playback notes (this is why the previous version felt dead):
 *  - one [MediaPlayer] per sound is PREPARED ASYNCHRONOUSLY as soon as the
 *    sound is configured, so tapping the wheel never waits on disk+codec;
 *  - starting a sound FLUSHES any still-playing one (open over close and
 *    vice versa) instead of overlapping through a single recycled player;
 *  - everything is best-effort: a broken file can never break the wheel.
 */
object WheelSoundStore {
    private const val PREFS = "spen_wheel_sounds"
    private const val KEY_OPEN = "open_sound_uri"
    private const val KEY_CLOSE = "close_sound_uri"

    /** Prepared players keyed by the sound they play; all idle ones die together. */
    private var openPlayer: MediaPlayer? = null
    private var closePlayer: MediaPlayer? = null
    private var preparedOpenUri: Uri? = null
    private var preparedCloseUri: Uri? = null

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getOpenSound(context: Context): Uri? =
        prefs(context).getString(KEY_OPEN, null)?.let(Uri::parse)

    fun getCloseSound(context: Context): Uri? =
        prefs(context).getString(KEY_CLOSE, null)?.let(Uri::parse)

    fun setOpenSound(context: Context, uri: Uri?) = setSound(context, KEY_OPEN, uri)

    fun setCloseSound(context: Context, uri: Uri?) = setSound(context, KEY_CLOSE, uri)

    private fun setSound(context: Context, key: String, uri: Uri?) {
        prefs(context).edit {
            if (uri == null) remove(key) else putString(key, uri.toString())
        }
        // The stored sound changed: drop the prepared player so the next
        // play() rebuilds it from the new file (and never plays stale audio).
        val open = key == KEY_OPEN
        if (open) {
            releasePlayer(openPlayer); openPlayer = null; preparedOpenUri = null
        } else {
            releasePlayer(closePlayer); closePlayer = null; preparedCloseUri = null
        }
        // Prewarm right away: by the time the user next opens/closes the
        // wheel the codec is ready and the sound plays with zero latency.
        prewarm(context.applicationContext, open, uri)
    }

    /** Starts background preparation of [uri] so the first tap already sounds. */
    private fun prewarm(context: Context, open: Boolean, uri: Uri?) {
        if (uri == null) return
        val current = if (open) openPlayer else closePlayer
        val currentUri = if (open) preparedOpenUri else preparedCloseUri
        if (current != null && currentUri == uri) return
        prepareInBackground(context, open, uri)
    }

    /**
     * Copies the picked audio into app-private storage and returns a stable
     * local Uri to persist. Returns null when the copy fails; callers keep
     * the previously stored value in that case.
     *
     * MP3 hardening (this fixed the "importing mp3 does nothing" bug):
     *  - the copy is retried once — the first openInputStream right after the
     *    SAF picker resolves can transiently fail on some providers;
     *  - the destination keeps the ORIGINAL extension (best effort via the
     *    content display name), because some codec paths sniff the container
     *    from the file name and silently refuse a nameless ".audio" blob;
     *  - the import is verified with MediaPlayer.prepare() BEFORE returning:
     *    a file the codec cannot decode is rejected immediately instead of
     *    storing a sound that never plays.
     */
    fun importSound(context: Context, source: Uri, open: Boolean): Uri? {
        val appContext = context.applicationContext
        repeat(2) { attempt ->
            try {
                val target = java.io.File(appContext.filesDir, targetNameFor(appContext, source, open))
                val input = appContext.contentResolver.openInputStream(source)
                    ?: return@repeat
                input.use { stream ->
                    java.io.File(target.absolutePath + ".part").outputStream().use { output ->
                        stream.copyTo(output)
                    }
                }
                val staged = java.io.File(target.absolutePath + ".part")
                if (staged.length() == 0L) {
                    staged.delete()
                    return@repeat
                }
                // Rename atomically only after a complete, decodable copy.
                if (!verifyPlayable(appContext, Uri.fromFile(staged))) {
                    staged.delete()
                    return@repeat
                }
                if (target.exists()) target.delete()
                if (!staged.renameTo(target)) {
                    staged.delete()
                    return@repeat
                }
                return Uri.fromFile(target)
            } catch (_: Exception) {
                // Only the LAST attempt reports failure: the caller then
                // keeps the previously stored sound untouched.
                if (attempt == 1) return null
            }
        }
        return null
    }

    /** Destination file name, preserving the original extension when known. */
    private fun targetNameFor(context: Context, source: Uri, open: Boolean): String {
        val base = if (open) "wheel_open_audio" else "wheel_close_audio"
        val suffix = queryExtension(context, source) ?: "mp3"
        return "$base.$suffix"
    }

    private fun queryExtension(context: Context, source: Uri): String? {
        return try {
            context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)
                            ?.substringAfterLast('.')
                            ?.lowercase()
                            ?.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
                    } else {
                        null
                    }
                }
        } catch (_: Exception) {
            null
        }
    }

    /** True when MediaPlayer can actually decode the staged file. */
    private fun verifyPlayable(context: Context, uri: Uri): Boolean = try {
        MediaPlayer.create(context, uri) != null
    } catch (_: Exception) {
        false
    }

    /**
     * Plays the stored sound for [open]; silent no-op when none is set.
     *
     * MP3 safety: the FIRST play of a given sound prepares the player
     * SYNCHRONOUSLY (a small local file takes milliseconds) — an async-only
     * first play would silently swallow the sound whenever the background
     * prepare loses the race with start(). Later plays reuse the cached
     * player, so steady-state stays instant.
     */
    fun play(context: Context, open: Boolean) {
        val appContext = context.applicationContext
        val uri = if (open) getOpenSound(appContext) else getCloseSound(appContext)
        if (uri == null) return
        try {
            // Starting one sound stops the other: open/close never overlap.
            if (open) {
                closePlayer?.run { try { pause() } catch (_: Exception) { } }
            } else {
                openPlayer?.run { try { pause() } catch (_: Exception) { } }
            }
            var player = if (open) openPlayer else closePlayer
            val preparedUri = if (open) preparedOpenUri else preparedCloseUri
            if (player == null || preparedUri != uri) {
                releasePlayer(player)
                player = buildPlayer(appContext, uri)
                if (open) {
                    openPlayer = player; preparedOpenUri = uri
                } else {
                    closePlayer = player; preparedCloseUri = uri
                }
            }
            player.seekTo(0)
            player.start()
        } catch (_: Exception) {
            // A broken custom sound must never break the wheel itself; drop
            // the broken player so the next play rebuilds it cleanly.
            if (open) {
                releasePlayer(openPlayer); openPlayer = null; preparedOpenUri = null
            } else {
                releasePlayer(closePlayer); closePlayer = null; preparedCloseUri = null
            }
        }
    }

    private fun prepareInBackground(context: Context, open: Boolean, uri: Uri) {
        val thread = Thread {
            try {
                val player = buildPlayer(context, uri)
                // Only claim the slot when nobody else won the race in the
                // meantime: a synchronous play() may have already built and
                // started its own player for this same uri (MP3 first-tap
                // path) — releasing that one mid-playback would cut the sound.
                if (open) {
                    if (preparedOpenUri != uri) {
                        releasePlayer(openPlayer); openPlayer = player; preparedOpenUri = uri
                    } else {
                        releasePlayer(player)
                    }
                } else {
                    if (preparedCloseUri != uri) {
                        releasePlayer(closePlayer); closePlayer = player; preparedCloseUri = uri
                    } else {
                        releasePlayer(player)
                    }
                }
            } catch (_: Exception) {
                // Broken file: leave the slot empty; play() will retry sync.
            }
        }
        thread.isDaemon = true
        thread.priority = Thread.MAX_PRIORITY
        thread.start()
    }

    private fun buildPlayer(context: Context, uri: Uri): MediaPlayer = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        setDataSource(context, uri)
        isLooping = false
        setOnCompletionListener { mp ->
            try { mp.seekTo(0); mp.pause() } catch (_: Exception) { }
        }
        prepare()
    }

    private fun releasePlayer(player: MediaPlayer?) {
        try { player?.release() } catch (_: Exception) { }
    }

    /** Releases the prepared players (service shutdown / sound change). */
    fun release() {
        releasePlayer(openPlayer); openPlayer = null; preparedOpenUri = null
        releasePlayer(closePlayer); closePlayer = null; preparedCloseUri = null
    }
}
