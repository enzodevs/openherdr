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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
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
    data class Recording(val level: Float) : DictationState
    data object Transcribing : DictationState
}

/**
 * Push-to-talk dictation: [start] records 16 kHz mono audio, [stop] transcribes it with
 * Parakeet and returns the text. Nothing leaves the phone. The recognizer (~700 MB of RAM)
 * loads while the user is still speaking and is dropped by [release] when the UI is hidden.
 */
class Dictation(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private val loadLock = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var loading: Deferred<OfflineRecognizer>? = null
    private var recording: Job? = null
    private val samples = FloatBuffer()

    fun refresh() {
        if (_state.value is DictationState.Idle || _state.value is DictationState.Unavailable) {
            _state.value = if (SpeechModel.locate(context).installed) DictationState.Idle else DictationState.Unavailable
        }
    }

    /** Requires RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    fun start() {
        val model = SpeechModel.locate(context)
        if (!model.installed) {
            _state.value = DictationState.Unavailable
            return
        }
        if (recording != null) return
        loading = scope.async(Dispatchers.Default) { recognizer(model) }
        samples.clear()
        _state.value = DictationState.Recording(0f)
        recording = scope.launch(Dispatchers.IO) {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minBuffer, SAMPLE_RATE / 5 * 4),
            )
            val chunk = FloatArray(SAMPLE_RATE / 20)
            try {
                record.startRecording()
                while (isActive && samples.size < MAX_SECONDS * SAMPLE_RATE) {
                    val n = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) continue
                    samples.add(chunk, n)
                    _state.value = DictationState.Recording(rms(chunk, n))
                }
            } finally {
                record.stop()
                record.release()
            }
        }
    }

    /** Stops recording and returns the transcript (empty when nothing was understood). */
    suspend fun stop(): String {
        val job = recording ?: return ""
        recording = null
        job.cancel()
        job.join()
        if (samples.size < SAMPLE_RATE / 4) {
            _state.value = DictationState.Idle
            return ""
        }
        _state.value = DictationState.Transcribing
        return try {
            val engine = loading!!.await()
            withContext(Dispatchers.Default) {
                val stream = engine.createStream()
                try {
                    stream.acceptWaveform(samples.toArray(), SAMPLE_RATE)
                    engine.decode(stream)
                    engine.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            }
        } finally {
            samples.clear()
            _state.value = DictationState.Idle
        }
    }

    fun cancel() {
        recording?.cancel()
        recording = null
        samples.clear()
        if (_state.value !is DictationState.Unavailable) _state.value = DictationState.Idle
    }

    /** Frees the model's memory; it reloads on the next [start]. */
    fun release() {
        if (recording != null || _state.value == DictationState.Transcribing) return
        scope.launch {
            loadLock.withLock {
                recognizer?.release()
                recognizer = null
                loading = null
            }
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

    private fun rms(chunk: FloatArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) sum += chunk[i] * chunk[i]
        return sqrt(sum / n).toFloat()
    }

    /** A growable float array, so long dictations do not box every sample. */
    private class FloatBuffer {
        private var data = FloatArray(SAMPLE_RATE * 10)
        @Volatile var size = 0
            private set

        @Synchronized fun add(chunk: FloatArray, n: Int) {
            if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
            System.arraycopy(chunk, 0, data, size, n)
            size += n
        }

        @Synchronized fun toArray(): FloatArray = data.copyOf(size)

        @Synchronized fun clear() { size = 0 }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MAX_SECONDS = 300
    }
}
