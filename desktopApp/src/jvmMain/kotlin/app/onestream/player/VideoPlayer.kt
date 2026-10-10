package app.onestream.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.sun.jna.NativeLibrary
import org.jetbrains.skia.Image
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import java.io.File
import java.nio.ByteBuffer

class VlcUnavailable(message: String) : Exception(message)

data class Track(val id: Int, val name: String)

/** What the player needs to know about a stream. Mirrors the relevant bits of ExtractorLink. */
data class Media(
    val url: String,
    val referer: String? = null,
    val userAgent: String? = null,
)

object Vlc {
    /** Bundled VLC (installer resources / dev checkout), then $ONESTREAM_VLC, then a system install. */
    fun findDir(): File? = listOfNotNull(
        System.getenv("ONESTREAM_VLC") ?: System.getProperty("onestream.vlc"),
        System.getProperty("compose.application.resources.dir")?.let { "$it/vlc" },
        "C:/Program Files/VideoLAN/VLC",
    ).map(::File).firstOrNull { File(it, "libvlc.dll").exists() }

    val factory: MediaPlayerFactory by lazy { create() }

    private fun create(): MediaPlayerFactory {
        val dir = findDir()
        val args = mutableListOf("--no-video-title-show", "--no-osd", "--no-snapshot-preview", "--quiet")
        if (dir != null) {
            NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), dir.absolutePath)
            NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), dir.absolutePath)
        } else if (!NativeDiscovery().discover()) {
            throw VlcUnavailable("VLC runtime not found. Reinstall 1-stream or set ONESTREAM_VLC to a VLC folder.")
        }
        return try {
            MediaPlayerFactory(args)
        } catch (t: Throwable) {
            throw VlcUnavailable("Could not start VLC: ${t.message}")
        }
    }
}

/** Decodes with libVLC and exposes frames as a Compose [ImageBitmap]. Not thread-safe to construct; safe to read from UI. */
class VideoPlayer {
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var lengthMs by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var finished by mutableStateOf(false)
        private set
    /** Playback speed, 1 = normal. */
    var rate by mutableStateOf(1f)
        private set
    var muted by mutableStateOf(false)
        private set
    var audioDelayMs by mutableStateOf(0L)
        private set
    var subtitleDelayMs by mutableStateOf(0L)
        private set

    private var info: ImageInfo? = null
    private var bytes = ByteArray(0)

    private val bufferFormat = object : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            info = ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
            bytes = ByteArray(sourceWidth * sourceHeight * 4)
            return RV32BufferFormat(sourceWidth, sourceHeight)
        }

        override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
    }

    private val render = RenderCallback { _, nativeBuffers, _ ->
        val i = info ?: return@RenderCallback
        val buf = nativeBuffers[0]
        buf.rewind()
        if (buf.remaining() < bytes.size) return@RenderCallback
        buf.get(bytes)
        // Fresh immutable image per frame: libVLC keeps overwriting its buffer while Compose is still drawing the last one
        frame = Image.makeRaster(i, bytes.copyOf(), i.minRowBytes).toComposeImageBitmap()
    }

    private val player: EmbeddedMediaPlayer = Vlc.factory.mediaPlayers().newEmbeddedMediaPlayer().also { mp ->
        mp.videoSurface().set(
            Vlc.factory.videoSurfaces().newVideoSurface(bufferFormat, render, true)
        )
        mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) {
                this@VideoPlayer.playing = true; buffering = false; finished = false
                // A new stream starts with libVLC's defaults, so the user's choices are applied again
                if (rate != 1f) mp.controls().setRate(rate)
                if (audioDelayMs != 0L) mp.audio().setDelay(audioDelayMs * 1000)
                if (subtitleDelayMs != 0L) mp.subpictures().setDelay(subtitleDelayMs * 1000)
            }
            override fun paused(mp: MediaPlayer) { this@VideoPlayer.playing = false }
            override fun stopped(mp: MediaPlayer) { this@VideoPlayer.playing = false }
            override fun finished(mp: MediaPlayer) { this@VideoPlayer.playing = false; finished = true }
            override fun error(mp: MediaPlayer) { this@VideoPlayer.playing = false; error = "This stream could not be played." }
            override fun buffering(mp: MediaPlayer, newCache: Float) { buffering = newCache < 100f }
            override fun timeChanged(mp: MediaPlayer, newTime: Long) { acceptTime(newTime) }
            override fun lengthChanged(mp: MediaPlayer, newLength: Long) { if (newLength > 0) lengthMs = newLength }
        })
    }

    @Volatile private var seekTarget = -1L
    @Volatile private var seekUntil = 0L
    @Volatile private var lastTimeEvent = 0L
    @Volatile private var released = false

    /** After a seek libVLC keeps reporting the old position for a moment; those would make the bar jump back and forth. */
    private fun acceptTime(t: Long) {
        lastTimeEvent = System.currentTimeMillis()
        if (lastTimeEvent < seekUntil && kotlin.math.abs(t - seekTarget) > 2500) return
        positionMs = t
    }

    // Some streams (HLS especially) never send a length event, and a quiet stream sends few time events, so ask directly too.
    private val poller = Thread {
        while (!released) {
            runCatching {
                val st = player.status()
                if (lengthMs <= 0) st.length().takeIf { it > 0 }?.let { lengthMs = it }
                if (System.currentTimeMillis() - lastTimeEvent > 700 && st.isPlaying) st.time().takeIf { it >= 0 }?.let { acceptTime(it) }
            }
            // Windows would dim the screen and sleep during a long film otherwise
            if (player.status().isPlaying && System.currentTimeMillis() - lastWake > 30_000) { lastWake = System.currentTimeMillis(); keepAwake() }
            Thread.sleep(400)
        }
    }.apply { isDaemon = true; start() }
    private var lastWake = 0L

    private interface Kernel32 : com.sun.jna.Library { fun SetThreadExecutionState(flags: Int): Int }
    private fun keepAwake() {
        runCatching {
            // ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED: resets the idle timers once; repeated every 30 s while playing
            com.sun.jna.Native.load("kernel32", Kernel32::class.java).SetThreadExecutionState(0x00000001 or 0x00000002)
        }
    }

    fun play(media: Media, startMs: Long = 0) {
        error = null; finished = false; frame = null; positionMs = startMs; lengthMs = 0; seekTarget = -1
        val opts = buildList {
            media.referer?.takeIf { it.isNotBlank() }?.let { add(":http-referrer=$it") }
            media.userAgent?.takeIf { it.isNotBlank() }?.let { add(":http-user-agent=$it") }
            if (startMs > 0) add(":start-time=${startMs / 1000.0}")
        }
        // Run off the caller's thread: libVLC blocks while it opens the stream.
        Thread { player.media().play(media.url, *opts.toTypedArray()) }.apply { isDaemon = true }.start()
    }

    fun togglePause() = player.controls().let { if (player.status().isPlaying) it.pause() else it.play() }
    fun seekTo(ms: Long) {
        seekTarget = ms; seekUntil = System.currentTimeMillis() + 1500
        positionMs = ms; player.controls().setTime(ms)
    }
    fun setVolume(percent: Int) { player.audio().setVolume(percent.coerceIn(0, 200)) }

    fun changeSpeed(r: Float) { rate = r; player.controls().setRate(r) }
    fun changeMute(m: Boolean) { muted = m; player.audio().isMute = m }
    /** Positive delays the sound (use when it plays ahead of the picture). */
    fun setAudioDelay(ms: Long) { audioDelayMs = ms; player.audio().setDelay(ms * 1000) }
    /** Positive delays the subtitles. */
    fun setSubtitleDelay(ms: Long) { subtitleDelayMs = ms; player.subpictures().setDelay(ms * 1000) }
    fun audioTrack(): Int = player.audio().track()
    fun subtitleTrack(): Int = player.subpictures().track()

    fun audioTracks(): List<Track> = player.audio().trackDescriptions().map { Track(it.id(), it.description()) }
    fun subtitleTracks(): List<Track> = player.subpictures().trackDescriptions().map { Track(it.id(), it.description()) }
    fun setAudioTrack(id: Int) { player.audio().setTrack(id) }
    fun setSubtitleTrack(id: Int) { player.subpictures().setTrack(id) }
    fun addSubtitle(url: String) { player.subpictures().setSubTitleUri(url) }

    fun release() {
        released = true
        Thread {
            runCatching { player.controls().stop() }
            runCatching { player.release() }
        }.start()
    }
}