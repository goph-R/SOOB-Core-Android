package info.dynart.soob

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.max
import kotlin.random.Random

/**
 * The Android implementation of the SOOB audio bindings — the port of
 * SOOB-Core-Web's `src/host/audio.ts`.
 *
 * Sounds: [SoundPool] one-shots, with the same random non-repeating variant
 * pick as the desktop `SoundLibrary`. Music: two [MediaPlayer]s crossfaded by
 * a volume ramp on the main-thread handler (the `GainNode` ramp in MediaPlayer
 * terms), looped, behind a global music gain.
 *
 * Ogg Vorbis is decoded natively here, so unlike the web build there is no
 * codec fallback to worry about. Playback is paused and resumed with the
 * activity, and on audio-focus loss.
 *
 * Called from the GL thread; MediaPlayer transitions are posted to the main
 * thread so the ramp handler and the player agree on a thread.
 */
object Audio {

    private const val TAG = "SOOB"
    private const val TICK_MS = 16L

    private var pool: SoundPool? = null
    private val soundIds = HashMap<String, MutableList<Int>>()
    private val lastVariant = HashMap<String, Int>()

    private var musicResolver: (String) -> String? = { null }
    private var masterVol = 1f

    private var current: Track? = null
    private var desired: String? = null
    private var paused = false

    private lateinit var appContext: Context
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val handler = Handler(Looper.getMainLooper())

    private class Track(val player: MediaPlayer, val name: String) {
        var vol = 0f
        var target = 1f
        var step = 1f       // per tick
        var stopAtZero = false
        var released = false
    }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(attrs)
            .build()

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseMusic()
                    AudioManager.AUDIOFOCUS_GAIN -> resumeMusic()
                }
            }
            .build()
    }

    fun setMusicResolver(fn: (String) -> String?) {
        musicResolver = fn
    }

    /**
     * Register one sound variant. [path] is relative to the APK assets root
     * (`game/sounds/jump.wav`) and must be stored uncompressed — see the
     * `noCompress` note in the app module's build.gradle, without which
     * `openFd` throws.
     */
    fun addSound(name: String, path: String) {
        val p = pool ?: return
        try {
            Assets.manager().openFd(path).use { fd ->
                val id = p.load(fd, 1)
                soundIds.getOrPut(name) { ArrayList() }.add(id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sound skipped: $path ($e)")
        }
    }

    fun soundPlay(name: String) {
        val p = pool ?: return
        val ids = soundIds[name] ?: return
        if (ids.isEmpty()) return
        var i = 0
        if (ids.size > 1) {
            // uniformly random, avoid an immediate repeat (matches SoundLibrary)
            val prev = lastVariant[name]
            do {
                i = Random.nextInt(ids.size)
            } while (i == prev)
            lastVariant[name] = i
        }
        p.play(ids[i], 1f, 1f, 1, 0, 1f)
    }

    fun musicPlay(name: String, fade: Double, loop: Boolean) {
        // Same track already playing / requested → no-op, so re-entering a
        // scene doesn't restart the loop. Matches native music.h and the web host.
        if (name == desired) return
        desired = name
        val path = musicResolver(name)
        if (path == null) {
            Log.w(TAG, "musicPlay: unknown track $name")
            return
        }
        handler.post { startMusic(name, Assets.assetPath(path), fade, loop) }
    }

    fun musicStop(fade: Double) {
        desired = null
        handler.post { fadeOutCurrent(fade) }
    }

    fun musicVolume(gain: Double) {
        masterVol = gain.coerceIn(0.0, 1.0).toFloat()
        handler.post { current?.let { applyVolume(it) } }
    }

    // ---- lifecycle ----

    fun onResume() {
        paused = false
        requestFocus()
        resumeMusic()
    }

    fun onPause() {
        paused = true
        pauseMusic()
        abandonFocus()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        current?.let { releaseTrack(it) }
        current = null
        pool?.release()
        pool = null
        soundIds.clear()
        abandonFocus()
    }

    // ---- internals (main thread) ----

    private fun requestFocus() {
        val am = audioManager ?: return
        val req = focusRequest ?: return
        am.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        val req = focusRequest ?: return
        am.abandonAudioFocusRequest(req)
    }

    private fun pauseMusic() = handler.post {
        current?.let { t ->
            if (!t.released && t.player.isPlaying) t.player.pause()
        }
    }

    private fun resumeMusic() = handler.post {
        if (paused) return@post
        current?.let { t ->
            if (!t.released && !t.player.isPlaying) t.player.start()
        }
    }

    private fun startMusic(name: String, assetPath: String, fade: Double, loop: Boolean) {
        val old = current
        val fadeSec = max(0.001, fade).toFloat()

        val mp = MediaPlayer()
        val track = Track(mp, name)
        track.vol = 0f
        track.target = 1f
        track.step = (TICK_MS / 1000f) / fadeSec
        try {
            Assets.manager().openFd(assetPath).use { fd ->
                mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            mp.isLooping = loop
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener {
                if (current !== track) {      // superseded while preparing
                    releaseTrack(track)
                    return@setOnPreparedListener
                }
                if (!paused) it.start()
                tick()
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "music skipped: $assetPath ($e)")
            releaseTrack(track)
            return
        }

        current = track
        if (old != null) {
            old.target = 0f
            old.step = (TICK_MS / 1000f) / fadeSec
            old.stopAtZero = true
            fading.add(old)
        }
        tick()
    }

    private fun fadeOutCurrent(fade: Double) {
        val t = current ?: return
        current = null
        t.target = 0f
        t.step = (TICK_MS / 1000f) / max(0.001, fade).toFloat()
        t.stopAtZero = true
        fading.add(t)
        tick()
    }

    private val fading = ArrayList<Track>()
    private var ticking = false

    private val ticker = object : Runnable {
        override fun run() {
            ticking = false
            var again = false

            current?.let { t ->
                if (rampTrack(t)) again = true
            }
            val it = fading.iterator()
            while (it.hasNext()) {
                val t = it.next()
                if (rampTrack(t)) {
                    again = true
                } else if (t.released) {
                    it.remove()
                }
            }
            if (again) tick()
        }
    }

    /** Advance one track's ramp; returns true while it still needs ticking. */
    private fun rampTrack(t: Track): Boolean {
        if (t.released) return false
        if (t.vol < t.target) {
            t.vol = minOf(t.target, t.vol + t.step)
        } else if (t.vol > t.target) {
            t.vol = maxOf(t.target, t.vol - t.step)
        }
        applyVolume(t)
        if (t.vol == t.target) {
            if (t.stopAtZero && t.vol == 0f) releaseTrack(t)
            return false
        }
        return true
    }

    private fun applyVolume(t: Track) {
        if (t.released) return
        val v = t.vol * masterVol
        try {
            t.player.setVolume(v, v)
        } catch (e: IllegalStateException) {
            // player torn down under us — nothing to do
        }
    }

    private fun releaseTrack(t: Track) {
        if (t.released) return
        t.released = true
        try {
            t.player.reset()
            t.player.release()
        } catch (e: Exception) {
            Log.w(TAG, "music release: $e")
        }
    }

    private fun tick() {
        if (ticking) return
        ticking = true
        handler.postDelayed(ticker, TICK_MS)
    }
}
