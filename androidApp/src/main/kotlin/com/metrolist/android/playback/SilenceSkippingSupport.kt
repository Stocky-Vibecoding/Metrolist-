package com.metrolist.android.playback

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.metrolist.shared.playback.InstantSilenceDetector
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.instantSilenceSeekTarget
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

private const val MUSIC_MINIMUM_SILENCE_DURATION_US = 500_000L
private const val MUSIC_SILENCE_THRESHOLD_DB = -55.0
private const val EDGE_LEADING_WINDOW_MS = 10_000L
private const val EDGE_MAX_LEADING_TRIM_MS = 5_000L
private const val EDGE_TRAILING_WINDOW_MS = 12_000L
private const val EDGE_MIN_TRACK_DURATION_MS = 25_000L
private const val EDGE_TRAILING_CONFIRM_MS = 600L

/**
 * Контроллер умной PCM-нормализации (подтяжка тихих треков + мягкий антиклип-лимитер).
 * В отличие от штатной нормализации, никогда не глушит громкие треки ниже 1.0x —
 * важно для вывода максимального чистого сигнала на внешние усилители.
 */
object SmartLoudnessController {
    private const val PREFS_NAME = "metrolist_custom_audio_prefs"
    private const val KEY_ENABLED = "smart_loudness_boost_enabled"

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val isEnabled: Boolean
        get() = _enabled.value

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(
        context: Context,
        value: Boolean,
    ) {
        initialized = true
        _enabled.value = value
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, value)
            .apply()
    }
}

internal class SilenceSkippingSupport(
    context: Context,
    vararg extraProcessors: AudioProcessor,
    onAudioOutputEvent: ((String, Map<String, String>) -> Unit)? = null,
    onOutputSignal: (AudioOutputSignal) -> Unit = {},
) {
    init {
        SmartLoudnessController.init(context)
    }

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val edgeTrimmer = EdgeSilenceTrimAudioProcessor()
    private val smartLoudness = SmartLoudnessAudioProcessor()
    private val detector = SilenceDetectorAudioProcessor()
    private val audioPositionDiagnostics = AudioPositionDiagnostics()
    val playoutTracker = AudioPlayoutTracker()
    val positionDiagnostics: Map<String, String> get() = audioPositionDiagnostics.details
    val playbackHeadPosition: Long? get() = audioPositionDiagnostics.playbackHeadPosition()

    @Volatile private var equalizer: EqualizerAudioProcessor? = null

    @Volatile
    var audioOutputDetails: Map<String, String> = emptyMap()
        private set

    fun audioDiagnosticDetails(): Map<String, String> = positionDiagnostics + audioOutputDetails + equalizer?.diagnosticDetails().orEmpty()

    val renderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink {
                val equalizer = EqualizerAudioProcessor().also { this@SilenceSkippingSupport.equalizer = it }
                return pitchSafeAudioSink(
                    context,
                    enableAudioOutputPlaybackParams,
                    playoutTracker.wrap(musicAudioProcessorChain(equalizer, *extraProcessors, edgeTrimmer, smartLoudness, detector)),
                    outputProvider = {
                        playoutTracker.wrap(
                            audioPositionDiagnostics.wrap(
                                AudioTrackAudioOutputProvider.Builder(context).build(),
                                onOutputSignal = onOutputSignal,
                                onOutputEvent =
                                    onAudioOutputEvent?.let { callback ->
                                        { event, details ->
                                            audioOutputDetails = details
                                            if (event != "sample") callback(event, details)
                                        }
                                    },
                            ),
                        )
                    },
                )
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

    fun attach(player: ExoPlayer) {
        player.skipSilenceEnabled = false
        edgeTrimmer.enabled = true
        detector.enabled = Settings.shared.skipSilence && Settings.shared.skipSilenceInstant
        val playing = MutableStateFlow(player.isPlaying)
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    detector.resetTracking()
                    edgeTrimmer.updateTimeline(player.currentPosition, player.duration)
                    playing.value = isPlaying
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    detector.resetTracking()
                    edgeTrimmer.onTrackChanged(player.currentPosition)
                    smartLoudness.resetForNewTrack()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    detector.resetTracking()
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        edgeTrimmer.onTrackChanged(newPosition.positionMs)
                        smartLoudness.resetForNewTrack()
                    } else if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                        edgeTrimmer.resetForSeek(newPosition.positionMs)
                        smartLoudness.resetForNewTrack()
                    }
                }

                override fun onTimelineChanged(
                    timeline: Timeline,
                    reason: Int,
                ) {
                    edgeTrimmer.updateTimeline(player.currentPosition, player.duration)
                    detector.signalSilence()
                }
            }
        player.addListener(listener)

        scope.launch {
            playing.collectLatest { isPlaying ->
                edgeTrimmer.updateTimeline(player.currentPosition, player.duration)
                while (isActive && isPlaying) {
                    edgeTrimmer.updateTimeline(player.currentPosition, player.duration)
                    delay(200L)
                }
            }
        }

        scope.launch {
            try {
                snapshotFlow { Settings.shared.skipSilence to Settings.shared.skipSilenceInstant }
                    .combine(playing) { settings, isPlaying -> Triple(settings.first, settings.second, isPlaying) }
                    .collectLatest { (enabled, instant, isPlaying) ->
                        player.skipSilenceEnabled = false
                        edgeTrimmer.enabled = true
                        detector.enabled = enabled && instant
                        detector.resetTracking()
                        if (enabled && instant && isPlaying) {
                            detector.skipWhenConfirmed(
                                isPlaying = { player.isPlaying },
                                positionMs = { player.currentPosition },
                                durationMs = { player.duration },
                                seekTo = { position ->
                                    val curPos = player.currentPosition
                                    val dur = player.duration
                                    val inEdgeWindow =
                                        curPos < EDGE_LEADING_WINDOW_MS ||
                                            (dur > EDGE_MIN_TRACK_DURATION_MS && dur - curPos <= EDGE_TRAILING_WINDOW_MS)
                                    if (inEdgeWindow) {
                                        PlaybackSeekSource(
                                            "silence_skip",
                                            mapOf("playerId" to Integer.toHexString(System.identityHashCode(player))),
                                        ).record(curPos, position, player.currentMediaItem?.mediaId)
                                        player.seekTo(position)
                                    }
                                },
                            )
                        }
                    }
            } finally {
                player.removeListener(listener)
            }
        }
    }

    fun close() = scope.cancel()
}

internal fun pitchSafeAudioSink(
    context: Context,
    hardwarePlaybackParameters: Boolean,
    processors: AudioProcessorChain,
    outputProvider: () -> AudioOutputProvider = { AudioTrackAudioOutputProvider.Builder(context).build() },
): AudioSink {
    val sink =
        DefaultAudioSink
            .Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(hardwarePlaybackParameters)
            .setAudioOutputProvider(outputProvider())
            .setAudioProcessorChain(processors)
            .build()
    return object : ForwardingAudioSink(sink) {
        private var softwarePitch = !hardwarePlaybackParameters

        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
            if (!softwarePitch && playbackParameters.pitch != 1f) {
                softwarePitch = true
                sink.setAudioOutputProvider(
                    object : ForwardingAudioOutputProvider(outputProvider()) {
                        override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig =
                            super.getOutputConfig(formatConfig.buildUpon().setEnablePlaybackParameters(false).build())
                    },
                )
            }
            super.setPlaybackParameters(playbackParameters)
        }
    }
}

internal fun musicAudioProcessorChain(vararg processors: AudioProcessor): DefaultAudioSink.DefaultAudioProcessorChain =
    DefaultAudioSink.DefaultAudioProcessorChain(
        arrayOf(*processors),
        SilenceSkippingAudioProcessor(
            MUSIC_MINIMUM_SILENCE_DURATION_US,
            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
            SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
            SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
            (Short.MAX_VALUE * 10.0.pow(MUSIC_SILENCE_THRESHOLD_DB / 20.0)).toInt().toShort(),
        ),
        SonicAudioProcessor(),
    )

/**
 * Умная PCM-нормализация громкости в реальном времени (Boost-Only AGC + Peak-Safe Soft-Knee Limiter):
 * - Громкие треки (VK / Я.Музыка / современный мастеринг) идут 100% bit-exact pass-through (gain = 1.0x, без сжатия пиков).
 * - Тихие треки плавно подтягиваются вверх (до +9 дБ) к полке -10 dBFS RMS.
 * - Пик-лимитер мгновенно прибирает гейн при резком дропе после тихого интро, исключая перегруз.
 * - Тишина и фоновый шум ниже -42 dBFS игнорируются (шум в паузах не усиливается).
 */
internal class SmartLoudnessAudioProcessor : BaseAudioProcessor() {
    private var rmsEnvelope: Float = TARGET_RMS
    private var currentGain: Float = 1.0f

    @Volatile
    private var pendingReset: Boolean = false

    fun resetForNewTrack() {
        pendingReset = true
    }

    private fun applyResetIfNeeded() {
        if (!pendingReset) return
        pendingReset = false
        rmsEnvelope = TARGET_RMS
        currentGain = 1.0f
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        applyResetIfNeeded()
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        val channels = inputAudioFormat.channelCount
        val sampleRate = inputAudioFormat.sampleRate
        if (bytesPerFrame <= 0 || channels <= 0 || sampleRate <= 0) {
            passThrough(inputBuffer)
            return
        }

        val enabled = SmartLoudnessController.isEnabled
        if (!enabled && currentGain <= UNITY_BYPASS_THRESHOLD) {
            currentGain = 1.0f
            passThrough(inputBuffer)
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val startPos = inputBuffer.position()
        val frameCount = remaining / bytesPerFrame
        if (frameCount <= 0) {
            passThrough(inputBuffer)
            return
        }
        val usableBytes = frameCount * bytesPerFrame
        val totalSamples = frameCount * channels

        var sumSquares = 0.0
        var blockPeak = 0f
        var idx = startPos
        val endPos = startPos + usableBytes
        while (idx < endPos) {
            val normalized = inputBuffer.getShort(idx) / 32768f
            val absSample = abs(normalized)
            if (absSample > blockPeak) blockPeak = absSample
            sumSquares += (normalized * normalized).toDouble()
            idx += 2
        }
        val blockRms = sqrt(sumSquares / totalSamples.coerceAtLeast(1)).toFloat()
        val blockDurationSec = frameCount.toFloat() / sampleRate.toFloat()

        val targetGain: Float =
            if (!enabled) {
                1.0f
            } else if (blockRms < NOISE_GATE_RMS) {
                // В тишине/паузе держим текущий коэффициент, чтобы не вытягивать фоновый шум
                currentGain
            } else {
                val envTauSec = if (blockRms > rmsEnvelope) ENVELOPE_ATTACK_SEC else ENVELOPE_RELEASE_SEC
                val envAlpha = (1.0 - exp((-blockDurationSec / envTauSec).toDouble())).toFloat().coerceIn(0f, 1f)
                rmsEnvelope += (blockRms - rmsEnvelope) * envAlpha
                val rmsTarget = (TARGET_RMS / rmsEnvelope.coerceAtLeast(NOISE_GATE_RMS)).coerceIn(1.0f, MAX_BOOST_GAIN)
                val peakSafe =
                    if (blockPeak > 0.02f) {
                        (LIMITER_CEILING / blockPeak).coerceIn(1.0f, MAX_BOOST_GAIN)
                    } else {
                        MAX_BOOST_GAIN
                    }
                minOf(rmsTarget, peakSafe)
            }

        val startGain = currentGain
        val wouldClipAtStart = blockPeak * startGain > LIMITER_CEILING
        val gainTauSec =
            when {
                !enabled -> DISABLE_RAMP_SEC
                wouldClipAtStart -> GAIN_PEAK_DUCK_SEC
                targetGain < startGain -> GAIN_FAST_DOWN_SEC
                else -> GAIN_SLOW_UP_SEC
            }
        val gainAlpha = (1.0 - exp((-blockDurationSec / gainTauSec).toDouble())).toFloat().coerceIn(0f, 1f)
        val endGain = (startGain + (targetGain - startGain) * gainAlpha).coerceIn(1.0f, MAX_BOOST_GAIN)
        currentGain = endGain

        // Для уже громких треков (gain ≈ 1.0x) отдаём 100% чистый нетронутый PCM без лимитера
        if (startGain <= UNITY_BYPASS_THRESHOLD && endGain <= UNITY_BYPASS_THRESHOLD) {
            currentGain = 1.0f
            passThrough(inputBuffer)
            return
        }

        val out = replaceOutputBuffer(usableBytes)
        out.order(ByteOrder.LITTLE_ENDIAN)

        val invFrameCount = 1f / frameCount.toFloat()
        val gainDelta = endGain - startGain
        var offset = startPos
        for (frame in 0 until frameCount) {
            val frameGain = startGain + gainDelta * (frame * invFrameCount)
            for (ch in 0 until channels) {
                val sample = inputBuffer.getShort(offset) / 32768f
                val boosted = sample * frameGain
                val limited = softLimit(boosted)
                val pcmOut = (limited * 32767f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                out.putShort(pcmOut)
                offset += 2
            }
        }

        inputBuffer.position(endPos)
        out.flip()
    }

    private fun softLimit(x: Float): Float {
        val absX = abs(x)
        if (absX <= KNEE_START) return x
        val headroom = LIMITER_CEILING - KNEE_START
        val shaped = KNEE_START + headroom * tanh(((absX - KNEE_START) / headroom).toDouble()).toFloat()
        return if (x >= 0f) shaped else -shaped
    }

    private fun passThrough(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return
        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        pendingReset = false
        rmsEnvelope = TARGET_RMS
        currentGain = 1.0f
    }

    override fun onReset() {
        pendingReset = false
        rmsEnvelope = TARGET_RMS
        currentGain = 1.0f
    }

    companion object {
        // Полка громкости -10 dBFS RMS (уровень современных громких релизов без глушения)
        private const val TARGET_RMS = 0.316f

        // Игнорируем сигнал тише -42 dBFS, чтобы не вытягивать фоновый шум
        private const val NOISE_GATE_RMS = 0.008f

        // Максимум +9 дБ подтяжки вверх для тихих треков
        private const val MAX_BOOST_GAIN = 2.82f

        private const val UNITY_BYPASS_THRESHOLD = 1.005f

        // Мягкое колено с -1.1 dBFS, потолок -0.13 dBFS — работает только при активном бусте
        private const val KNEE_START = 0.88f
        private const val LIMITER_CEILING = 0.985f

        private const val ENVELOPE_ATTACK_SEC = 0.18f
        private const val ENVELOPE_RELEASE_SEC = 2.60f
        private const val GAIN_PEAK_DUCK_SEC = 0.015f
        private const val GAIN_FAST_DOWN_SEC = 0.12f
        private const val GAIN_SLOW_UP_SEC = 1.80f
        private const val DISABLE_RAMP_SEC = 0.04f
    }
}

/**
 * Обрезка мёртвой тишины (< −55 дБ) СТРОГО в начале (до 5 сек) и самом конце трека.
 * Вся середина песни проходит 100% без изменений (pass-through).
 */
internal class EdgeSilenceTrimAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var enabled: Boolean = true

    @Volatile
    private var positionMs: Long = 0L

    @Volatile
    private var durationMs: Long = C.TIME_UNSET

    @Volatile
    private var heardAudioInTrack: Boolean = false

    @Volatile
    private var pendingTrackStart: Boolean = true

    private var leadingTrimmedFrames: Long = 0L
    private var trailingSilentFrames: Long = 0L

    fun updateTimeline(
        currentPosMs: Long,
        totalDurationMs: Long,
    ) {
        if (currentPosMs >= 0L) positionMs = currentPosMs
        if (totalDurationMs > 0L) durationMs = totalDurationMs
    }

    fun onTrackChanged(startPosMs: Long) {
        positionMs = startPosMs.coerceAtLeast(0L)
        durationMs = C.TIME_UNSET
        pendingTrackStart = positionMs <= 1_500L
        heardAudioInTrack = !pendingTrackStart
        leadingTrimmedFrames = 0L
        trailingSilentFrames = 0L
    }

    fun resetForSeek(newPosMs: Long) {
        positionMs = newPosMs.coerceAtLeast(0L)
        pendingTrackStart = positionMs <= 1_500L
        heardAudioInTrack = !pendingTrackStart
        leadingTrimmedFrames = 0L
        trailingSilentFrames = 0L
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        val channels = inputAudioFormat.channelCount
        val sampleRate = inputAudioFormat.sampleRate
        if (!enabled || bytesPerFrame <= 0 || channels <= 0 || sampleRate <= 0) {
            passThrough(inputBuffer)
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val pos = positionMs
        val dur = durationMs

        // 1. НАЧАЛО ТРЕКА: режем мёртвую тишину до первого звука (не более EDGE_MAX_LEADING_TRIM_MS)
        if (!heardAudioInTrack) {
            val maxLeadingFrames = (sampleRate.toLong() * EDGE_MAX_LEADING_TRIM_MS) / 1_000L
            if (leadingTrimmedFrames >= maxLeadingFrames || (!pendingTrackStart && pos > EDGE_LEADING_WINDOW_MS)) {
                heardAudioInTrack = true
                pendingTrackStart = false
                passThrough(inputBuffer)
                return
            }

            val startPos = inputBuffer.position()
            val limit = inputBuffer.limit()
            val frameCount = (limit - startPos) / bytesPerFrame
            var firstAudibleFrame = -1

            for (frame in 0 until frameCount) {
                val frameOffset = startPos + frame * bytesPerFrame
                var peak = 0
                for (ch in 0 until channels) {
                    val sample = abs(inputBuffer.getShort(frameOffset + ch * 2).toInt())
                    if (sample > peak) peak = sample
                }
                if (peak > DEAD_SILENCE_PEAK) {
                    firstAudibleFrame = frame
                    break
                }
            }

            if (firstAudibleFrame < 0) {
                leadingTrimmedFrames += frameCount.toLong()
                if (leadingTrimmedFrames >= maxLeadingFrames) {
                    heardAudioInTrack = true
                    pendingTrackStart = false
                    passThrough(inputBuffer)
                } else {
                    inputBuffer.position(limit)
                }
                return
            }

            heardAudioInTrack = true
            pendingTrackStart = false
            val prerollFrames = (sampleRate / 50).coerceAtLeast(1)
            val keepFromFrame = (firstAudibleFrame - prerollFrames).coerceAtLeast(0)
            inputBuffer.position(startPos + keepFromFrame * bytesPerFrame)
            passThrough(inputBuffer)
            return
        }

        // 2. СЕРЕДИНА ТРЕКА: 100% pass-through
        val inTrailingWindow = dur > EDGE_MIN_TRACK_DURATION_MS && pos >= (dur - EDGE_TRAILING_WINDOW_MS)
        if (!inTrailingWindow) {
            trailingSilentFrames = 0L
            passThrough(inputBuffer)
            return
        }

        // 3. КОНЕЦ ТРЕКА: отбрасываем пустой хвост мёртвой тишины > 600 мс
        val startPos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val frameCount = (limit - startPos) / bytesPerFrame
        var hasAudibleSample = false

        for (frame in 0 until frameCount) {
            val frameOffset = startPos + frame * bytesPerFrame
            for (ch in 0 until channels) {
                if (abs(inputBuffer.getShort(frameOffset + ch * 2).toInt()) > DEAD_SILENCE_PEAK) {
                    hasAudibleSample = true
                    break
                }
            }
            if (hasAudibleSample) break
        }

        if (hasAudibleSample) {
            trailingSilentFrames = 0L
            passThrough(inputBuffer)
            return
        }

        trailingSilentFrames += frameCount.toLong()
        val confirmFrames = (sampleRate.toLong() * EDGE_TRAILING_CONFIRM_MS) / 1_000L
        if (trailingSilentFrames > confirmFrames) {
            inputBuffer.position(limit)
        } else {
            passThrough(inputBuffer)
        }
    }

    private fun passThrough(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return
        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        heardAudioInTrack = !pendingTrackStart && positionMs > 1_500L
        leadingTrimmedFrames = 0L
        trailingSilentFrames = 0L
    }

    override fun onReset() {
        heardAudioInTrack = false
        pendingTrackStart = true
        leadingTrimmedFrames = 0L
        trailingSilentFrames = 0L
        positionMs = 0L
        durationMs = C.TIME_UNSET
    }

    companion object {
        private val DEAD_SILENCE_PEAK: Int =
            (Short.MAX_VALUE * 10.0.pow(MUSIC_SILENCE_THRESHOLD_DB / 20.0)).toInt().coerceAtLeast(16)
    }
}

internal class SilenceDetectorAudioProcessor : BaseAudioProcessor() {
    @Volatile var enabled = false
        @Synchronized set(value) {
            if (field == value) return
            field = value
            resetTracking()
        }
    private var detector: InstantSilenceDetector? = null
    private val thresholds = Channel<Long>(Channel.CONFLATED)
    private var generation = 0L
    private var pendingInput = AudioProcessor.EMPTY_BUFFER
    private var pendingOutput = AudioProcessor.EMPTY_BUFFER

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (pendingOutput.hasRemaining()) return
        pendingInput.position(pendingInput.limit())
        pendingInput = AudioProcessor.EMPTY_BUFFER
        pendingOutput = AudioProcessor.EMPTY_BUFFER
        if (!inputBuffer.hasRemaining()) return
        if (enabled) {
            synchronized(this) {
                val activeDetector = detector
                if (enabled && activeDetector != null) {
                    inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    var offset = inputBuffer.position()
                    while (offset + inputAudioFormat.bytesPerFrame <= inputBuffer.limit()) {
                        var peak = 0
                        repeat(inputAudioFormat.channelCount) {
                            peak = maxOf(peak, abs(inputBuffer.getShort(offset + it * 2).toInt()))
                        }
                        val wasSilent = activeDetector.isSilent
                        activeDetector.frame(peak)
                        if (wasSilent != activeDetector.isSilent) {
                            generation++
                            if (activeDetector.isSilent) thresholds.trySend(generation)
                        }
                        offset += inputAudioFormat.bytesPerFrame
                    }
                }
            }
        }
        pendingInput = inputBuffer
        pendingOutput = inputBuffer.duplicate().order(inputBuffer.order())
    }

    override fun getOutput(): ByteBuffer {
        super.getOutput()
        return pendingOutput
    }

    override fun isEnded(): Boolean = super.isEnded() && !pendingOutput.hasRemaining()

    internal suspend fun skipWhenConfirmed(
        isPlaying: () -> Boolean,
        positionMs: () -> Long,
        durationMs: () -> Long,
        seekTo: (Long) -> Unit,
    ) {
        for (threshold in thresholds) {
            if (!isPlaying() || !isSilent(threshold)) continue
            delay(200)
            if (!isPlaying() || !isSilent(threshold)) continue
            val target = instantSilenceSeekTarget(positionMs(), durationMs()) ?: continue
            resetTracking()
            seekTo(target)
            delay(350)
        }
    }

    @Synchronized private fun isSilent(threshold: Long): Boolean = enabled && generation == threshold && detector?.isSilent == true

    @Synchronized fun signalSilence() {
        if (enabled && detector?.isSilent == true) thresholds.trySend(generation)
    }

    @Synchronized fun resetTracking() {
        generation++
        detector?.reset()
    }

    @Synchronized
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        pendingInput = AudioProcessor.EMPTY_BUFFER
        pendingOutput = AudioProcessor.EMPTY_BUFFER
        detector =
            if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
                InstantSilenceDetector(inputAudioFormat.sampleRate)
            } else {
                null
            }
        resetTracking()
    }

    @Synchronized
    override fun onReset() {
        pendingInput = AudioProcessor.EMPTY_BUFFER
        pendingOutput = AudioProcessor.EMPTY_BUFFER
        detector = null
        resetTracking()
    }
}
