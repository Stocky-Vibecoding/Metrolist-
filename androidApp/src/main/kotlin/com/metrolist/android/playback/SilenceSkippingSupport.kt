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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

private const val MUSIC_MINIMUM_SILENCE_DURATION_US = 500_000L
private const val MUSIC_SILENCE_THRESHOLD_DB = -55.0
private const val EDGE_LEADING_WINDOW_MS = 10_000L
private const val EDGE_TRAILING_WINDOW_MS = 12_000L
private const val EDGE_MIN_TRACK_DURATION_MS = 25_000L
private const val EDGE_TRAILING_CONFIRM_MS = 600L

internal class SilenceSkippingSupport(
    context: Context,
    vararg extraProcessors: AudioProcessor,
    onAudioOutputEvent: ((String, Map<String, String>) -> Unit)? = null,
    onOutputSignal: (AudioOutputSignal) -> Unit = {},
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val edgeTrimmer = EdgeSilenceTrimAudioProcessor()
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
                    playoutTracker.wrap(musicAudioProcessorChain(equalizer, *extraProcessors, edgeTrimmer, detector)),
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
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    detector.resetTracking()
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        edgeTrimmer.onTrackChanged(newPosition.positionMs)
                    } else if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                        edgeTrimmer.resetForSeek(newPosition.positionMs)
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
 * Обрезка мёртвой тишины (< −55 дБ) СТРОГО в начале и самом конце трека.
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
        trailingSilentFrames = 0L
    }

    fun resetForSeek(newPosMs: Long) {
        positionMs = newPosMs.coerceAtLeast(0L)
        pendingTrackStart = positionMs <= 1_500L
        heardAudioInTrack = !pendingTrackStart
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

        // Media3 отдаёт 16-bit PCM в little-endian — выставляем порядок байтов явно
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val pos = positionMs
        val dur = durationMs

        // 1. НАЧАЛО ТРЕКА: режем мёртвую тишину строго до первого звука (в пределах первых 10 сек)
        if (!heardAudioInTrack) {
            if (!pendingTrackStart && pos > EDGE_LEADING_WINDOW_MS) {
                heardAudioInTrack = true
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
                inputBuffer.position(limit)
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
        trailingSilentFrames = 0L
    }

    override fun onReset() {
        heardAudioInTrack = false
        pendingTrackStart = true
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
                if (enabled) {
                    val detector = checkNotNull(detector)
                    inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    var offset = inputBuffer.position()
                    while (offset + inputAudioFormat.bytesPerFrame <= inputBuffer.limit()) {
                        var peak = 0
                        repeat(inputAudioFormat.channelCount) {
                            peak = maxOf(peak, abs(inputBuffer.getShort(offset + it * 2).toInt()))
                        }
                        val wasSilent = detector.isSilent
                        detector.frame(peak)
                        if (wasSilent != detector.isSilent) {
                            generation++
                            if (detector.isSilent) thresholds.trySend(generation)
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
