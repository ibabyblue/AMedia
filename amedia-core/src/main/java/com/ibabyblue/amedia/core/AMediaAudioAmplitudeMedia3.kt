@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ibabyblue.amedia.core

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class AMediaAudioAmplitudeRuntime(
    internal val configuration: AMediaAudioAmplitudeConfiguration,
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    coroutineDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : AMediaAudioAmplitudeSource {
    internal data class Target(
        val epoch: Long,
        val entryId: String,
        val generation: Long,
        val volume: Float,
    )

    private val scope = CoroutineScope(SupervisorJob() + coroutineDispatcher)
    private val released = AtomicBoolean(false)
    private val observing = AtomicBoolean(false)
    private val epoch = AtomicLong(0L)
    private val target = AtomicReference<Target?>(null)
    private val unavailableReason = AtomicReference<AMediaAudioAmplitudeUnavailableReason?>(null)
    private val latestPlaybackState = AtomicReference<AMediaAudioAmplitudePlaybackContext?>(null)
    private val mutableState = MutableStateFlow<AMediaAudioAmplitudeState>(
        AMediaAudioAmplitudeState.Inactive(AMediaAudioAmplitudeInactiveReason.NoMedia),
    )
    override val audioAmplitudeState: StateFlow<AMediaAudioAmplitudeState> = mutableState.asStateFlow()

    init {
        scope.launch {
            mutableState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest(::setObserved)
        }
    }

    fun bind(playbackState: StateFlow<AMediaState>) {
        scope.launch {
            playbackState.collectLatest { state -> updatePlayback(state.toAmplitudePlaybackContext()) }
        }
    }

    internal fun updatePlayback(context: AMediaAudioAmplitudePlaybackContext) {
        if (released.get()) return
        latestPlaybackState.set(context)
        refresh(context)
    }

    fun currentTarget(): Target? = target.get().takeIf {
        !released.get() && observing.get() && unavailableReason.get() == null
    }

    fun publish(target: Target, rawPeak: Float, rawRms: Float) {
        if (currentTarget() != target) return
        val volume = target.volume.coerceIn(0f, 1f)
        mutableState.value = AMediaAudioAmplitudeState.Active(
            entryId = target.entryId,
            generation = target.generation,
            level = AMediaAudioAmplitudeLevel(
                peak = (rawPeak * volume).coerceIn(0f, 1f),
                rms = (rawRms * volume).coerceIn(0f, 1f),
            ),
            sampledAtElapsedRealtimeNanos = elapsedRealtimeNanos().coerceAtLeast(0L),
        )
    }

    fun markOutputAvailable() {
        if (unavailableReason.getAndSet(null) != null) {
            invalidateAndRefresh()
        }
    }

    fun markUnavailable(reason: AMediaAudioAmplitudeUnavailableReason) {
        if (released.get()) return
        unavailableReason.set(reason)
        target.set(null)
        epoch.incrementAndGet()
        mutableState.value = AMediaAudioAmplitudeState.Unavailable(reason)
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        observing.set(false)
        target.set(null)
        epoch.incrementAndGet()
        mutableState.value = AMediaAudioAmplitudeState.Inactive(
            AMediaAudioAmplitudeInactiveReason.Released,
        )
        scope.cancel()
    }

    private fun setObserved(observed: Boolean) {
        if (released.get()) return
        observing.set(observed)
        invalidateAndRefresh()
    }

    private fun invalidateAndRefresh() {
        epoch.incrementAndGet()
        target.set(null)
        latestPlaybackState.get()?.let(::refresh)
    }

    private fun refresh(state: AMediaAudioAmplitudePlaybackContext) {
        unavailableReason.get()?.let { reason ->
            target.set(null)
            mutableState.value = AMediaAudioAmplitudeState.Unavailable(reason)
            return
        }
        val inactiveReason = state.inactiveAmplitudeReason()
        val entryId = state.entryId
        if (inactiveReason != null || entryId == null || !observing.get()) {
            val newReason = inactiveReason ?: AMediaAudioAmplitudeInactiveReason.AwaitingSamples
            if (target.getAndSet(null) != null) epoch.incrementAndGet()
            mutableState.value = AMediaAudioAmplitudeState.Inactive(newReason)
            return
        }
        val current = target.get()
        if (
            current == null ||
            current.entryId != entryId ||
            current.generation != state.generation ||
            current.volume != state.volume
        ) {
            val next = Target(
                epoch = epoch.incrementAndGet(),
                entryId = entryId,
                generation = state.generation,
                volume = state.volume,
            )
            target.set(next)
            mutableState.value = AMediaAudioAmplitudeState.Inactive(
                AMediaAudioAmplitudeInactiveReason.AwaitingSamples,
            )
        }
    }
}

internal data class AMediaAudioAmplitudePlaybackContext(
    val entryId: String?,
    val generation: Long,
    val status: AMediaPlaybackStatus,
    val isPlaying: Boolean,
    val isMuted: Boolean,
    val volume: Float,
    val hasSelectedAudioTrack: Boolean?,
)

private fun AMediaState.toAmplitudePlaybackContext(): AMediaAudioAmplitudePlaybackContext =
    AMediaAudioAmplitudePlaybackContext(
        entryId = currentEntry?.entryId,
        generation = generation,
        status = status,
        isPlaying = isPlaying,
        isMuted = isMuted,
        volume = volume,
        hasSelectedAudioTrack = tracks
            .takeIf(List<AMediaTrack>::isNotEmpty)
            ?.any { track ->
                track.type == AMediaTrackType.Audio && track.selected && track.supported
            },
    )

private fun AMediaAudioAmplitudePlaybackContext.inactiveAmplitudeReason():
    AMediaAudioAmplitudeInactiveReason? {
    if (entryId == null) return AMediaAudioAmplitudeInactiveReason.NoMedia
    if (hasSelectedAudioTrack == false) {
        return AMediaAudioAmplitudeInactiveReason.NoAudioTrack
    }
    if (isMuted || volume == 0f) return AMediaAudioAmplitudeInactiveReason.Muted
    return when (status) {
        AMediaPlaybackStatus.Idle -> AMediaAudioAmplitudeInactiveReason.NoMedia
        AMediaPlaybackStatus.Preparing -> AMediaAudioAmplitudeInactiveReason.Preparing
        AMediaPlaybackStatus.Buffering -> AMediaAudioAmplitudeInactiveReason.Buffering
        AMediaPlaybackStatus.Ended -> AMediaAudioAmplitudeInactiveReason.Ended
        AMediaPlaybackStatus.Failed -> AMediaAudioAmplitudeInactiveReason.Failed
        AMediaPlaybackStatus.Paused,
        AMediaPlaybackStatus.Ready,
        -> AMediaAudioAmplitudeInactiveReason.Paused
        AMediaPlaybackStatus.Playing -> if (isPlaying) null else {
            AMediaAudioAmplitudeInactiveReason.Paused
        }
    }
}

internal class AMediaAudioAmplitudeRenderersFactory(
    context: Context,
    configuration: AMediaAudioAmplitudeConfiguration,
) : DefaultRenderersFactory(context.applicationContext) {
    internal val runtime: AMediaAudioAmplitudeRuntime = AMediaAudioAmplitudeRuntime(configuration)

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setAudioOutputProvider(
            PcmOnlyAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()),
        )
        .setEnableFloatOutput(false)
        .setEnableAudioOutputPlaybackParameters(false)
        .setAudioProcessorChain(
            OutputAmplitudeAudioProcessorChain(
                TeeAudioProcessor(PcmAmplitudeBufferSink(runtime)),
            ),
        )
        .build()
}

internal fun audioAmplitudeRuntime(
    renderersFactory: RenderersFactory?,
): AMediaAudioAmplitudeRuntime? =
    (renderersFactory as? AMediaAudioAmplitudeRenderersFactory)?.runtime

private class OutputAmplitudeAudioProcessorChain(
    teeAudioProcessor: TeeAudioProcessor,
) : AudioProcessorChain {
    private val delegate = DefaultAudioSink.DefaultAudioProcessorChain()
    private val processors: Array<AudioProcessor> = delegate.audioProcessors + teeAudioProcessor

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters =
        delegate.applyPlaybackParameters(playbackParameters)

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean =
        delegate.applySkipSilenceEnabled(skipSilenceEnabled)

    override fun getMediaDuration(playoutDuration: Long): Long =
        delegate.getMediaDuration(playoutDuration)

    override fun getSkippedOutputFrameCount(): Long = delegate.skippedOutputFrameCount
}

private class PcmOnlyAudioOutputProvider(
    private val delegate: AudioOutputProvider,
) : AudioOutputProvider {
    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        val pcmConfig = formatConfig.buildUpon().setEnableOffload(false).build()
        if (Util.isEncodingLinearPcm(formatConfig.format.pcmEncoding)) {
            return delegate.getFormatSupport(pcmConfig).buildUpon()
                .setIsFormatSupportedForOffload(false)
                .setIsGaplessSupportedForOffload(false)
                .setIsSpeedChangeSupportedForOffload(false)
                .build()
        }
        // The sink must reject encoded input here. MediaCodecAudioRenderer then verifies the sink's
        // 16-bit PCM support and selects a decoder instead of bypassing compressed data to us.
        return AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(AudioOutputProvider.FORMAT_UNSUPPORTED)
            .build()
    }

    override fun getOutputConfig(
        formatConfig: AudioOutputProvider.FormatConfig,
    ): AudioOutputProvider.OutputConfig {
        if (!Util.isEncodingLinearPcm(formatConfig.format.pcmEncoding)) {
            throw AudioOutputProvider.ConfigurationException("Audio amplitude requires PCM output")
        }
        return delegate.getOutputConfig(formatConfig.buildUpon().setEnableOffload(false).build())
            .buildUpon()
            .setIsOffload(false)
            .setUseOffloadGapless(false)
            .build()
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
        delegate.getAudioOutput(config)

    override fun addListener(listener: AudioOutputProvider.Listener) = delegate.addListener(listener)

    override fun removeListener(listener: AudioOutputProvider.Listener) = delegate.removeListener(listener)

    override fun setClock(clock: Clock) = delegate.setClock(clock)

    override fun release() = delegate.release()
}

internal class PcmAmplitudeBufferSink(
    private val runtime: AMediaAudioAmplitudeRuntime,
) : TeeAudioProcessor.AudioBufferSink {
    private var sampleRateHz: Int = 0
    private var channelCount: Int = 0
    private var encoding: Int = C.ENCODING_INVALID
    private var samplesPerWindow: Int = 1
    private var localEpoch: Long = Long.MIN_VALUE
    private var sampleCount: Int = 0
    private var squareSum: Double = 0.0
    private var peak: Float = 0f

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount
        this.encoding = encoding
        samplesPerWindow = max(
            1,
            sampleRateHz * channelCount / runtime.configuration.updatesPerSecond,
        )
        resetWindow(Long.MIN_VALUE)
        if (encoding in SUPPORTED_ENCODINGS && sampleRateHz > 0 && channelCount > 0) {
            runtime.markOutputAvailable()
        } else {
            runtime.markUnavailable(AMediaAudioAmplitudeUnavailableReason.UnsupportedOutput)
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val target = runtime.currentTarget() ?: return
        if (encoding !in SUPPORTED_ENCODINGS || sampleRateHz <= 0 || channelCount <= 0) return
        if (localEpoch != target.epoch) resetWindow(target.epoch)
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            while (hasCompleteSample(buffer, encoding)) {
                val sample = readSample(buffer, encoding)
                peak = max(peak, abs(sample))
                squareSum += sample.toDouble() * sample.toDouble()
                sampleCount += 1
                if (sampleCount >= samplesPerWindow) {
                    runtime.publish(
                        target = target,
                        rawPeak = peak.coerceIn(0f, 1f),
                        rawRms = sqrt(squareSum / sampleCount).toFloat().coerceIn(0f, 1f),
                    )
                    resetWindow(target.epoch)
                }
            }
        } catch (_: RuntimeException) {
            runtime.markUnavailable(
                AMediaAudioAmplitudeUnavailableReason.UnsupportedPlaybackPipeline,
            )
        }
    }

    private fun resetWindow(epoch: Long) {
        localEpoch = epoch
        sampleCount = 0
        squareSum = 0.0
        peak = 0f
    }

    private companion object {
        val SUPPORTED_ENCODINGS: Set<Int> = setOf(
            C.ENCODING_PCM_8BIT,
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT,
        )

        fun hasCompleteSample(buffer: ByteBuffer, encoding: Int): Boolean = buffer.remaining() >= when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> Int.MAX_VALUE
        }

        fun readSample(buffer: ByteBuffer, encoding: Int): Float = when (encoding) {
            C.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xFF) - 128) / 128f
            C.ENCODING_PCM_16BIT -> buffer.short / 32768f
            C.ENCODING_PCM_24BIT -> {
                val value = (buffer.get().toInt() and 0xFF) or
                    ((buffer.get().toInt() and 0xFF) shl 8) or
                    (buffer.get().toInt() shl 16)
                value / 8_388_608f
            }
            C.ENCODING_PCM_32BIT -> buffer.int / 2_147_483_648f
            C.ENCODING_PCM_FLOAT -> buffer.float.takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
            else -> 0f
        }
    }
}
