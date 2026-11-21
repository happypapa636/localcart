package app.localcart.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

/**
 * Records HD voice notes as AAC (ADTS) at 44.1 kHz mono, a format both the Nexconn HD voice message
 * and Gemini audio understanding accept directly.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean {
        stopAndDiscard()
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val out = File(dir, "voice-${UUID.randomUUID()}.aac")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioChannels(1)
            r.setMaxDuration(MAX_SECONDS * 1000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            out.delete()
            false
        }
    }

    fun elapsedSeconds(): Int = if (recorder == null) 0 else ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()

    /** Stops and returns the file with its duration, or null if it was too short to be useful. */
    fun stop(): Pair<File, Int>? {
        val r = recorder ?: return null
        val out = file
        val seconds = max(1, ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt())
        recorder = null
        file = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (!ok || out == null || !out.exists() || out.length() < 1_000) {
            out?.delete()
            return null
        }
        return out to seconds
    }

    fun stopAndDiscard() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
        file?.delete()
        file = null
    }

    companion object {
        const val MAX_SECONDS = 120
    }
}

/** Plays one voice note at a time, local file or remote URL. */
class VoicePlayer {
    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing.asStateFlow()

    fun toggle(key: String, source: String) {
        if (_playing.value == key) {
            stop()
            return
        }
        stop()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(source.removePrefix("file://"))
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener { stop() }
            p.setOnErrorListener { _, _, _ -> stop(); true }
            p.prepareAsync()
            player = p
            _playing.value = key
        } catch (e: Exception) {
            runCatching { p.release() }
            _playing.value = null
        }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        _playing.value = null
    }
}

/** Camera capture targets and photo preparation for uploads and image messages. */
object ImageFiles {
    fun newCaptureUri(context: Context): Pair<Uri, File> {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "photo-${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return uri to file
    }

    /** Decodes, downsizes to at most `maxSide` px and re-encodes as JPEG into the cache. */
    suspend fun prepareJpeg(context: Context, uri: Uri, maxSide: Int = 1600): File = withContext(Dispatchers.IO) {
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val size = info.size
                val scale = maxSide.toFloat() / max(size.width, size.height)
                if (scale < 1f) decoder.setTargetSize((size.width * scale).toInt(), (size.height * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(uri).use { input ->
                val raw = BitmapFactory.decodeStream(input) ?: error("Could not read that photo")
                val scale = maxSide.toFloat() / max(raw.width, raw.height)
                if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
            }
        }
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val out = File(dir, "upload-${UUID.randomUUID()}.jpg")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        out
    }
}
