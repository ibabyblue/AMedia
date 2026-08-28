@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ibabyblue.amedia.core

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AMediaAudioAmplitudeTest {
    @Test
    fun configurationAcceptsOnlySupportedUpdateRate() {
        assertEquals(10, AMediaAudioAmplitudeConfiguration(10).updatesPerSecond)
        assertEquals(30, AMediaAudioAmplitudeConfiguration(30).updatesPerSecond)
        assertFails { AMediaAudioAmplitudeConfiguration(9) }
        assertFails { AMediaAudioAmplitudeConfiguration(31) }
    }

    @Test
    fun levelRejectsInvalidNormalizedValues() {
        assertFails { AMediaAudioAmplitudeLevel(-0.01f, 0f) }
        assertFails { AMediaAudioAmplitudeLevel(1.01f, 0f) }
        assertFails { AMediaAudioAmplitudeLevel(0f, Float.NaN) }
    }

    @Test
    fun pcmEncodingsProduceNormalizedPeakAndRms() = runTest {
        val runtime = runtime()
        val collection = observe(runtime)
        runtime.updatePlayback(playing())
        runCurrent()
        val sink = PcmAmplitudeBufferSink(runtime)

        listOf(
            C.ENCODING_PCM_8BIT to pcm8(192),
            C.ENCODING_PCM_16BIT to pcm16(16_384),
            C.ENCODING_PCM_24BIT to pcm24(4_194_304),
            C.ENCODING_PCM_32BIT to pcm32(1_073_741_824),
            C.ENCODING_PCM_FLOAT to pcmFloat(0.5f),
        ).forEach { (encoding, buffer) ->
            sink.flush(sampleRateHz = 10, channelCount = 1, encoding = encoding)
            sink.handleBuffer(buffer)
            val active = runtime.audioAmplitudeState.value as AMediaAudioAmplitudeState.Active
            assertEquals(0.5f, active.level.peak, 0.0001f)
            assertEquals(0.5f, active.level.rms, 0.0001f)
        }

        collection.cancelAndJoin()
        runtime.release()
    }

    @Test
    fun stereoWindowUsesAllSamplesForPeakAndRms() = runTest {
        val runtime = runtime()
        val collection = observe(runtime)
        runtime.updatePlayback(playing())
        runCurrent()
        val sink = PcmAmplitudeBufferSink(runtime)
        sink.flush(sampleRateHz = 10, channelCount = 2, encoding = C.ENCODING_PCM_16BIT)

        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(Short.MAX_VALUE)
            .putShort(0)
            .flipForReading()
        sink.handleBuffer(buffer)

        val active = runtime.audioAmplitudeState.value as AMediaAudioAmplitudeState.Active
        assertEquals(1f, active.level.peak, 0.0001f)
        assertEquals(kotlin.math.sqrt(0.5f), active.level.rms, 0.0001f)
        collection.cancelAndJoin()
        runtime.release()
    }

    @Test
    fun amplitudeIsScaledByPlayerVolume() = runTest {
        val runtime = runtime()
        val collection = observe(runtime)
        runtime.updatePlayback(playing(volume = 0.5f))
        runCurrent()
        val sink = PcmAmplitudeBufferSink(runtime)
        sink.flush(10, 1, C.ENCODING_PCM_16BIT)
        sink.handleBuffer(pcm16(Short.MAX_VALUE.toInt()))

        val active = runtime.audioAmplitudeState.value as AMediaAudioAmplitudeState.Active
        assertEquals(0.5f, active.level.peak, 0.0001f)
        assertEquals(0.5f, active.level.rms, 0.0001f)
        collection.cancelAndJoin()
        runtime.release()
    }

    @Test
    fun noSubscriberSkipsPcmAggregationUntilObserved() = runTest {
        val runtime = runtime()
        runtime.updatePlayback(playing())
        val sink = PcmAmplitudeBufferSink(runtime)
        sink.flush(10, 1, C.ENCODING_PCM_16BIT)
        sink.handleBuffer(pcm16(Short.MAX_VALUE.toInt()))

        assertNull(runtime.currentTarget())
        assertTrue(runtime.audioAmplitudeState.value is AMediaAudioAmplitudeState.Inactive)

        val collection = observe(runtime)
        runCurrent()
        sink.handleBuffer(pcm16(Short.MAX_VALUE.toInt()))
        assertTrue(runtime.audioAmplitudeState.value is AMediaAudioAmplitudeState.Active)
        collection.cancelAndJoin()
        runtime.release()
    }

    @Test
    fun staleGenerationCannotPublishIntoNewEntry() = runTest {
        val runtime = runtime()
        val collection = observe(runtime)
        runtime.updatePlayback(playing(entryId = "first", generation = 1L))
        runCurrent()
        val staleTarget = checkNotNull(runtime.currentTarget())

        runtime.updatePlayback(playing(entryId = "second", generation = 2L))
        runtime.publish(staleTarget, rawPeak = 1f, rawRms = 1f)

        val state = runtime.audioAmplitudeState.value
        assertTrue(state is AMediaAudioAmplitudeState.Inactive)
        assertEquals(
            AMediaAudioAmplitudeInactiveReason.AwaitingSamples,
            (state as AMediaAudioAmplitudeState.Inactive).reason,
        )
        collection.cancelAndJoin()
        runtime.release()
    }

    @Test
    fun playbackStateTransitionsClearActiveAmplitude() = runTest {
        val runtime = runtime()
        val collection = observe(runtime)
        runtime.updatePlayback(playing())
        runCurrent()
        val sink = PcmAmplitudeBufferSink(runtime)
        sink.flush(10, 1, C.ENCODING_PCM_16BIT)
        sink.handleBuffer(pcm16(Short.MAX_VALUE.toInt()))
        assertTrue(runtime.audioAmplitudeState.value is AMediaAudioAmplitudeState.Active)

        runtime.updatePlayback(playing(isMuted = true))
        assertInactive(runtime, AMediaAudioAmplitudeInactiveReason.Muted)
        runtime.updatePlayback(playing(status = AMediaPlaybackStatus.Buffering, isPlaying = false))
        assertInactive(runtime, AMediaAudioAmplitudeInactiveReason.Buffering)
        runtime.updatePlayback(playing(status = AMediaPlaybackStatus.Ended, isPlaying = false))
        assertInactive(runtime, AMediaAudioAmplitudeInactiveReason.Ended)

        runtime.release()
        assertInactive(runtime, AMediaAudioAmplitudeInactiveReason.Released)
        collection.cancelAndJoin()
    }

    @Test
    fun unsupportedPcmBecomesUnavailableAndRecoversOnSupportedFormat() = runTest {
        val runtime = runtime()
        val collection = observe(runtime)
        runtime.updatePlayback(playing())
        runCurrent()
        val sink = PcmAmplitudeBufferSink(runtime)

        sink.flush(10, 1, C.ENCODING_MP3)
        assertEquals(
            AMediaAudioAmplitudeUnavailableReason.UnsupportedOutput,
            (runtime.audioAmplitudeState.value as AMediaAudioAmplitudeState.Unavailable).reason,
        )

        sink.flush(10, 1, C.ENCODING_PCM_16BIT)
        assertInactive(runtime, AMediaAudioAmplitudeInactiveReason.AwaitingSamples)
        collection.cancelAndJoin()
        runtime.release()
    }

    private fun kotlinx.coroutines.test.TestScope.runtime(): AMediaAudioAmplitudeRuntime =
        AMediaAudioAmplitudeRuntime(
            configuration = AMediaAudioAmplitudeConfiguration(updatesPerSecond = 10),
            elapsedRealtimeNanos = { 123L },
            coroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

    private fun kotlinx.coroutines.test.TestScope.observe(runtime: AMediaAudioAmplitudeRuntime) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.audioAmplitudeState.collect()
        }

    private fun playing(
        entryId: String = "entry",
        generation: Long = 1L,
        status: AMediaPlaybackStatus = AMediaPlaybackStatus.Playing,
        isPlaying: Boolean = true,
        isMuted: Boolean = false,
        volume: Float = 1f,
    ): AMediaAudioAmplitudePlaybackContext = AMediaAudioAmplitudePlaybackContext(
        entryId = entryId,
        generation = generation,
        status = status,
        isPlaying = isPlaying,
        isMuted = isMuted,
        volume = volume,
        hasSelectedAudioTrack = true,
    )

    private fun assertInactive(
        runtime: AMediaAudioAmplitudeRuntime,
        reason: AMediaAudioAmplitudeInactiveReason,
    ) {
        val state = runtime.audioAmplitudeState.value as AMediaAudioAmplitudeState.Inactive
        assertEquals(reason, state.reason)
    }

    private fun pcm8(value: Int): ByteBuffer = ByteBuffer.allocate(1)
        .put(value.toByte())
        .flipForReading()

    private fun pcm16(value: Int): ByteBuffer = ByteBuffer.allocate(2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(value.toShort())
        .flipForReading()

    private fun pcm24(value: Int): ByteBuffer = ByteBuffer.allocate(3)
        .put((value and 0xFF).toByte())
        .put((value shr 8 and 0xFF).toByte())
        .put((value shr 16 and 0xFF).toByte())
        .flipForReading()

    private fun pcm32(value: Int): ByteBuffer = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .flipForReading()

    private fun pcmFloat(value: Float): ByteBuffer = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putFloat(value)
        .flipForReading()

    private fun ByteBuffer.flipForReading(): ByteBuffer = apply { flip() }

    private fun assertFails(action: () -> Unit) {
        var failed = false
        try {
            action()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
