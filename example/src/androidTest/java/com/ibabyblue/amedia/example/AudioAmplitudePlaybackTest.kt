package com.ibabyblue.amedia.example

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ibabyblue.amedia.cache.AMediaCacheConfiguration
import com.ibabyblue.amedia.cache.AMediaCacheFactory
import com.ibabyblue.amedia.cache.AMediaCachedControllerFactory
import com.ibabyblue.amedia.core.AMediaAudioAmplitudeConfiguration
import com.ibabyblue.amedia.core.AMediaAudioAmplitudeInactiveReason
import com.ibabyblue.amedia.core.AMediaAudioAmplitudeSource
import com.ibabyblue.amedia.core.AMediaAudioAmplitudeState
import com.ibabyblue.amedia.core.AMediaAudioPolicy
import com.ibabyblue.amedia.core.AMediaCachePolicy
import com.ibabyblue.amedia.core.AMediaController
import com.ibabyblue.amedia.core.AMediaControllerConfiguration
import com.ibabyblue.amedia.core.AMediaControllerFactory
import com.ibabyblue.amedia.core.AMediaItem
import com.ibabyblue.amedia.core.AMediaQueueEntry
import com.ibabyblue.amedia.core.audioAmplitudeSourceOrNull
import com.ibabyblue.amedia.preload.AMediaPreloadSession
import com.ibabyblue.amedia.preload.AMediaPreloadSessionFactory
import java.util.concurrent.FutureTask
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioAmplitudePlaybackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun defaultControllerDoesNotInstallAmplitudeCapability() {
        val controller = onMain { AMediaControllerFactory.create(context) }
        try {
            assertNull(controller.audioAmplitudeSourceOrNull())
        } finally {
            onMain(controller::release)
        }
    }

    @Test
    fun localAudioPublishesAmplitudeAndMuteMakesItInactive() = runBlocking {
        val controller = createAmplitudeController()
        try {
            onMain {
                controller.setMedia(audioItem("local-audio"), playWhenReady = true)
            }
            assertNotNull(awaitActive(controller))

            onMain { controller.setMuted(true) }
            val muted = awaitState(controller) {
                it == AMediaAudioAmplitudeState.Inactive(
                    AMediaAudioAmplitudeInactiveReason.Muted,
                )
            }
            assertEquals(
                AMediaAudioAmplitudeInactiveReason.Muted,
                (muted as AMediaAudioAmplitudeState.Inactive).reason,
            )
        } finally {
            onMain(controller::release)
        }
    }

    @Test
    fun videoAudioTrackPublishesAmplitudeAfterSpeedProcessing() = runBlocking {
        val controller = createAmplitudeController()
        try {
            onMain {
                controller.setSpeed(2f)
                controller.setMedia(videoItem("local-video"), playWhenReady = true)
            }
            assertNotNull(awaitActive(controller))
        } finally {
            onMain(controller::release)
        }
    }

    @Test
    fun cachedAudioPublishesAmplitudeAndWritesBytes() = runBlocking {
        val cache = AMediaCacheFactory.create(
            context,
            AMediaCacheConfiguration(
                directoryName = "amedia-amplitude-test-${System.nanoTime()}",
                maxBytes = 8L * 1024L * 1024L,
            ),
        )
        val controller = onMain {
            AMediaCachedControllerFactory.create(
                context = context,
                cache = cache,
                configuration = instrumentationControllerConfiguration(),
                audioAmplitudeConfiguration = AMediaAudioAmplitudeConfiguration(),
            )
        }
        try {
            val item = audioItem("cached-audio", AMediaCachePolicy.ReadThrough)
            onMain { controller.setMedia(item, playWhenReady = true) }
            assertNotNull(awaitActive(controller))
            withTimeout(TEST_TIMEOUT_MILLIS) {
                while (cache.cachedBytes(item) == 0L) {
                    kotlinx.coroutines.yield()
                }
            }
        } finally {
            onMain(controller::release)
            cache.clear()
            cache.close()
        }
    }

    @Test
    fun preloadedAudioPublishesOnlyWhenPlayed() = runBlocking {
        val session = onMain {
            AMediaPreloadSessionFactory.create(
                context = context,
                controllerConfiguration = instrumentationControllerConfiguration(),
                audioAmplitudeConfiguration = AMediaAudioAmplitudeConfiguration(),
            )
        }
        try {
            val entry = AMediaQueueEntry("preloaded-audio", audioItem("preloaded-audio"))
            val source = requireNotNull(session.playback.audioAmplitudeSourceOrNull())
            onMain {
                session.update(listOf(entry))
                session.hint(entry.entryId)
            }
            assertEquals(
                AMediaAudioAmplitudeInactiveReason.NoMedia,
                (source.audioAmplitudeState.value as AMediaAudioAmplitudeState.Inactive).reason,
            )

            onMain { session.play(entry.entryId) }
            assertNotNull(awaitActive(session.playback))
        } finally {
            onMain(session::release)
        }
    }

    private fun createAmplitudeController(): AMediaController = onMain {
        AMediaControllerFactory.create(
            context = context,
            configuration = instrumentationControllerConfiguration(),
            audioAmplitudeConfiguration = AMediaAudioAmplitudeConfiguration(),
        )
    }

    private fun instrumentationControllerConfiguration(): AMediaControllerConfiguration =
        AMediaControllerConfiguration(
            // Instrumentation is not a foreground UI and Android may harden its focus request.
            audioPolicy = AMediaAudioPolicy.Ambient,
        )

    private fun audioItem(
        id: String,
        cachePolicy: AMediaCachePolicy = AMediaCachePolicy.None,
    ): AMediaItem = AMediaItem(
        mediaId = id,
        uri = resourceUri(R.raw.sample_audio),
        mimeType = "audio/mp4",
        cachePolicy = cachePolicy,
    )

    private fun videoItem(id: String): AMediaItem = AMediaItem(
        mediaId = id,
        uri = resourceUri(R.raw.sample_video),
        mimeType = "video/mp4",
    )

    private fun resourceUri(resourceId: Int): Uri = Uri.Builder()
        .scheme("android.resource")
        .authority(context.packageName)
        .appendPath(resourceId.toString())
        .build()

    private suspend fun awaitActive(
        controller: AMediaController,
    ): AMediaAudioAmplitudeState.Active = awaitState(controller) {
        it is AMediaAudioAmplitudeState.Active
    } as AMediaAudioAmplitudeState.Active

    private suspend fun awaitState(
        controller: AMediaController,
        predicate: (AMediaAudioAmplitudeState) -> Boolean,
    ): AMediaAudioAmplitudeState {
        val source: AMediaAudioAmplitudeSource =
            requireNotNull(controller.audioAmplitudeSourceOrNull())
        return withTimeoutOrNull(TEST_TIMEOUT_MILLIS) {
            source.audioAmplitudeState.first(predicate)
        } ?: error(
            "Timed out waiting for amplitude; amplitude=${source.audioAmplitudeState.value}, " +
                "playback=${controller.state.value}",
        )
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
        return task.get()
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS: Long = 15_000L
    }
}
