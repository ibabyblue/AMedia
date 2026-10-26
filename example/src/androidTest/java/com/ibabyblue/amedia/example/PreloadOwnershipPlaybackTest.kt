@file:OptIn(com.ibabyblue.amedia.core.ExperimentalAMediaApi::class)

package com.ibabyblue.amedia.example

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ibabyblue.amedia.cache.AMediaCacheConfiguration
import com.ibabyblue.amedia.cache.AMediaCacheFactory
import com.ibabyblue.amedia.core.AMediaAudioPolicy
import com.ibabyblue.amedia.core.AMediaControllerConfiguration
import com.ibabyblue.amedia.core.AMediaItem
import com.ibabyblue.amedia.core.AMediaQueueEntry
import com.ibabyblue.amedia.core.AMediaRepeatMode
import com.ibabyblue.amedia.preload.AMediaPreloadSession
import com.ibabyblue.amedia.preload.AMediaPreloadSessionFactory
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreloadOwnershipPlaybackTest {
    @Test fun playingVideoSurvivesEvictionAndRepeatedSwitchesKeepLoadersBounded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val baselineLoaders = loaderCount()
        val cache = AMediaCacheFactory.create(context,
            AMediaCacheConfiguration(directoryName = "preload-playback-${UUID.randomUUID()}"))
        val directory = cache.directory
        lateinit var session: AMediaPreloadSession
        fun entry(index: Int): AMediaQueueEntry {
            val resource = if (index % 2 == 0) R.raw.sample_video else R.raw.sample_video_two
            return AMediaQueueEntry("video-$index", AMediaItem(
                mediaId = "video-$index",
                uri = Uri.parse("android.resource://${context.packageName}/$resource"),
            ))
        }
        onMain {
            session = AMediaPreloadSessionFactory.create(context, cache,
                controllerConfiguration = AMediaControllerConfiguration(
                    initiallyMuted = true,
                    audioPolicy = AMediaAudioPolicy.MutedInlineVideo,
                    repeatMode = AMediaRepeatMode.One,
                ))
        }
        try {
            onMain {
                session.update((0..2).map(::entry))
                session.play("video-0")
            }
            await("initial video playback") { session.playback.state.value.isPlaying }
            onMain { session.update((1..3).map(::entry)) }
            SystemClock.sleep(150)
            assertEquals("video-0", session.playback.state.value.currentEntry?.entryId)
            assertTrue("eviction must preserve active playback", session.playback.state.value.isPlaying)

            for (index in 1..20) {
                onMain {
                    session.update((index..index + 2).sortedBy { it % 3 }.map(::entry))
                    session.play("video-$index")
                }
                await("switch to video-$index") {
                    val state = session.playback.state.value
                    state.currentEntry?.entryId == "video-$index" && state.isPlaying
                }
                await("bounded loaders after video-$index") { loaderCount() <= baselineLoaders + 4 }
            }
        } finally {
            onMain { session.release() }
            cache.close()
            await("loaders released") { loaderCount() <= baselineLoaders }
            directory.deleteRecursively()
        }
    }

    private fun loaderCount() = Thread.getAllStackTraces().keys.count {
        it.isAlive && it.name == "ExoPlayer:Loader:ProgressiveMediaPeriod"
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue(description, condition())
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
