package dev.cambraia.openherdr.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** NVIDIA Parakeet TDT 0.6B v3 (int8, sherpa-onnx export): 25 European languages, punctuation, on device. */
class SpeechModel(val dir: File) {
    val encoder = File(dir, "encoder.int8.onnx")
    val decoder = File(dir, "decoder.int8.onnx")
    val joiner = File(dir, "joiner.int8.onnx")
    val tokens = File(dir, "tokens.txt")

    val installed: Boolean
        get() = listOf(encoder, decoder, joiner, tokens).all { it.isFile && it.length() > 0 }

    companion object {
        const val NAME = "parakeet-tdt-0.6b-v3-int8"

        /** Where `adb push` can put the model without root: the app's external files directory. */
        fun locate(context: Context): SpeechModel {
            val candidates = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
                .map { SpeechModel(File(it, "models/$NAME")) }
            return candidates.firstOrNull { it.installed } ?: candidates.first()
        }
    }
}

sealed interface DictationState {
    data object Unavailable : DictationState
    data object Idle : DictationState

    /** A take in progress; [paused] keeps what was said so far and frees the microphone. */
    data class Recording(val level: Float, val seconds: Int, val paused: Boolean) : DictationState

    /** Piece [done] of [total] is being recognized; [total] is 0 while the model loads. */
    data class Transcribing(val done: Int, val total: Int) : DictationState

    /** The take could not be turned into text. Its audio is still on disk: retry, export or discard. */
    data class Failed(val message: String, val seconds: Int) : DictationState
}

/**
 * Dictation that does not lose what was said. [start] records 16 kHz mono audio straight to a
 * file, so the take survives the app being killed; [pause] and [resume] extend the same take;
 * [finish] recognizes it with Parakeet in pieces and hands the text to [onText]. A take that
 * cannot be recognized stays on disk as [DictationState.Failed] until the user retries, exports
 * or discards it. Nothing leaves the phone. The recognizer (~700 MB of RAM) loads while the user
 * is still speaking and is dropped by [release] when the UI is hidden.
 */
class Dictation(private val context: Context, private val scope: CoroutineScope) {
    /** Receives the text of a finished take, with the tag given to [start]. Called off the main thread. */
    var onText: (tag: String, text: String) -> Unit = { _, _ -> }

    private val dir = File(context.filesDir, "dictation")
    private val audio = File(dir, "take.pcm")
    private val tagFile = File(dir, "take.tag")

    private val _state = MutableStateFlow(
        // A take left behind by a killed process comes back as something to retry or export.
        if (audio.length() > 0) DictationState.Failed("The app closed before this recording was transcribed.", seconds()) else DictationState.Idle,
    )
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private val control = Mutex()
    private val loadLock = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var recording: Job? = null

    private fun seconds(): Int = (audio.length() / 2 / SAMPLE_RATE).toInt()

    fun refresh() {
        if (_state.value is DictationState.Idle || _state.value is DictationState.Unavailable) {
            _state.value = if (SpeechModel.locate(context).installed) DictationState.Idle else DictationState.Unavailable
        }
    }

    /** Starts a new take whose text goes to [tag]. Requires RECORD_AUDIO. */
    fun start(tag: String) = command {
        val model = SpeechModel.locate(context)
        if (!model.installed) {
            _state.value = DictationState.Unavailable
            return@command
        }
        if (_state.value != DictationState.Idle) return@command
        dir.mkdirs()
        audio.delete()
        tagFile.writeText(tag)
        scope.launch(Dispatchers.Default) { runCatching { recognizer(model) } }
        capture()
    }

    fun pause() = command {
        if ((_state.value as? DictationState.Recording)?.paused != false) return@command
        stopCapture()
        _state.value = DictationState.Recording(0f, seconds(), paused = true)
    }

    fun resume() = command {
        if ((_state.value as? DictationState.Recording)?.paused == true && seconds() < MAX_SECONDS) capture()
    }

    /** Ends the take and transcribes it; the text arrives through [onText]. */
    fun finish() = command {
        if (_state.value is DictationState.Recording) transcribe()
    }

    /** Tries again to transcribe a take that failed. */
    fun retry() = command {
        if (_state.value is DictationState.Failed) transcribe()
    }

    /** Throws the current take away. */
    fun discard() = command {
        if (_state.value is DictationState.Transcribing) return@command
        stopCapture()
        clear()
        _state.value = DictationState.Idle
        refresh()
    }

    /** Writes the kept take as a WAV file. Blocking. */
    fun export(out: OutputStream) {
        val bytes = audio.length()
        out.write(AudioSegments.wavHeader(bytes, SAMPLE_RATE))
        audio.inputStream().use { it.copyTo(out) }
    }

    /** Frees the model's memory; it reloads on the next [start]. */
    fun release() {
        if (_state.value != DictationState.Idle && _state.value != DictationState.Unavailable) return
        scope.launch {
            loadLock.withLock {
                recognizer?.release()
                recognizer = null
            }
        }
    }

    /** Control calls run one at a time, in order, off the caller's thread. */
    private fun command(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) { control.withLock { block() } }
    }

    private suspend fun stopCapture() {
        recording?.cancelAndJoin()
        recording = null
    }

    private fun clear() {
        audio.delete()
        tagFile.delete()
    }

    @SuppressLint("MissingPermission")
    private fun capture() {
        _state.value = DictationState.Recording(0f, seconds(), paused = false)
        recording = scope.launch(Dispatchers.IO) {
            var full = false
            try {
                val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, SAMPLE_RATE * 2),
                )
                val chunk = ShortArray(SAMPLE_RATE / 20)
                val bytes = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                try {
                    if (record.state != AudioRecord.STATE_INITIALIZED) throw IOException("The microphone is not available")
                    // Unbuffered appends: every 50 ms of speech is on disk before the next is read.
                    FileOutputStream(audio, true).use { out ->
                        var written = audio.length() / 2
                        record.startRecording()
                        while (isActive && !full) {
                            val n = record.read(chunk, 0, chunk.size)
                            if (n < 0) throw IOException("The microphone stopped ($n)")
                            if (n == 0) continue
                            bytes.clear()
                            bytes.asShortBuffer().put(chunk, 0, n)
                            out.write(bytes.array(), 0, n * 2)
                            written += n
                            full = written >= MAX_SECONDS.toLong() * SAMPLE_RATE
                            _state.value = DictationState.Recording(rms(chunk, n), (written / SAMPLE_RATE).toInt(), paused = false)
                        }
                    }
                } finally {
                    runCatching { record.stop() }
                    record.release()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The microphone was lost: keep what was recorded and let the user decide.
                _state.value = DictationState.Recording(0f, seconds(), paused = true)
            }
            // At the limit the take is transcribed without waiting for the user.
            if (full) finish()
        }
    }

    private suspend fun transcribe() {
        stopCapture()
        val length = seconds()
        if (audio.length() < SAMPLE_RATE / 4 * 2) {
            clear()
            _state.value = DictationState.Idle
            return
        }
        _state.value = DictationState.Transcribing(0, 0)
        try {
            val engine = recognizer(SpeechModel.locate(context))
            val text = withContext(Dispatchers.Default) {
                val samples = AudioSegments.floats(audio.readBytes())
                val pieces = AudioSegments.split(samples, SAMPLE_RATE)
                pieces.mapIndexed { index, piece ->
                    _state.value = DictationState.Transcribing(index, pieces.size)
                    val stream = engine.createStream()
                    try {
                        stream.acceptWaveform(samples.copyOfRange(piece.first, piece.last + 1), SAMPLE_RATE)
                        engine.decode(stream)
                        engine.getResult(stream).text.trim()
                    } finally {
                        stream.release()
                    }
                }.filter { it.isNotEmpty() }.joinToString(" ")
            }
            if (text.isEmpty()) {
                _state.value = DictationState.Failed("No speech was recognized in this recording.", length)
                return
            }
            onText(runCatching { tagFile.readText() }.getOrDefault(""), text)
            clear()
            _state.value = DictationState.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Includes running out of memory: the audio file is untouched either way.
            _state.value = DictationState.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Transcription failed.", length)
        }
    }

    private suspend fun recognizer(model: SpeechModel): OfflineRecognizer = loadLock.withLock {
        recognizer ?: OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = model.encoder.path,
                        decoder = model.decoder.path,
                        joiner = model.joiner.path,
                    ),
                    tokens = model.tokens.path,
                    modelType = "nemo_transducer",
                    numThreads = 4,
                ),
                decodingMethod = "greedy_search",
            ),
        ).also { recognizer = it }
    }

    private fun rms(chunk: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) sum += (chunk[i] / 32768.0).let { it * it }
        return sqrt(sum / n).toFloat()
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** Ten minutes: long enough for a spoken brief, short enough to transcribe in a couple of minutes. */
        const val MAX_SECONDS = 600
    }
}
