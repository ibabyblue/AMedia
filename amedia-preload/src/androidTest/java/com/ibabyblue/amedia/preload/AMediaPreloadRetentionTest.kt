@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@file:OptIn(com.ibabyblue.amedia.core.ExperimentalAMediaApi::class)

package com.ibabyblue.amedia.preload

import android.net.Uri
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ibabyblue.amedia.core.AMediaCommandResult
import com.ibabyblue.amedia.core.AMediaItem
import com.ibabyblue.amedia.core.AMediaQueueEntry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real AMedia/Media3 ownership boundary, without network or media decoding. */
@RunWith(AndroidJUnit4::class)
class AMediaPreloadRetentionTest {
    @Test fun slidingWindowEvictsOldSourcesAndPreservesStableSlots() = withSession { session, manager ->
        val removed = mutableListOf<MediaSource>()
        onMain {
            var previous = emptyMap<String, MediaSource>()
            repeat(40) { start ->
                val entries = (start until start + 3).sortedBy { it % 3 }.map(::entry)
                assertEquals(AMediaCommandResult.Accepted, session.update(entries))
                assertEquals("window $start", 3, manager.sourceCount)
                val current = managedSources(session, manager)
                previous.forEach { (id, source) ->
                    if (id in current) assertSame(source, current[id])
                    else {
                        assertNull(manager.getMediaSource(source.mediaItem))
                        removed += source
                    }
                }
                previous = current
            }
        }
        removed.forEach { assertReleased(it) }
    }

    @Test fun replacingAnEntryReleasesItsPreviousSource() = withSession { session, manager ->
        val removed = mutableListOf<MediaSource>()
        onMain {
            repeat(20) { revision ->
                removed += managedSources(session, manager).values
                session.update(listOf(entry(0, revision)))
                assertEquals(1, manager.sourceCount)
            }
            removed.forEach { assertNull(manager.getMediaSource(it.mediaItem)) }
        }
        removed.forEach { assertReleased(it) }
    }

    @Test fun reorderingReleasesReplacedWrappersEvenWhenMediaItemsAreEqual() = withSession { session, manager ->
        lateinit var previous: Map<String, MediaSource>
        onMain {
            session.update(listOf(entry(0), entry(1), entry(2)))
            previous = managedSources(session, manager)
            session.update(listOf(entry(2), entry(0), entry(1)))
            assertEquals(3, manager.sourceCount)
            managedSources(session, manager).forEach { (id, source) -> assertNotSame(previous[id], source) }
        }
        // Count alone misses this case: an unreleased wrapper can be overwritten under the same key.
        previous.values.forEach { assertReleased(it) }
    }

    @Test fun emptyUpdateReleasesAllManagedSourcesAndClearsTheHint() = withSession { session, manager ->
        lateinit var previous: Collection<MediaSource>
        onMain {
            session.update(listOf(entry(0), entry(1), entry(2)))
            session.hint("entry-1")
            previous = managedSources(session, manager).values
            session.update(emptyList())
            assertEquals(0, manager.sourceCount)
            assertTrue(session.state.value.entryIds.isEmpty())
            assertNull(session.state.value.anchorEntryId)
        }
        previous.forEach { assertReleased(it) }
    }

    @Test fun unchangedUpdateRetainsSourcesAndReleaseRemainsIdempotent() = withSession { session, manager ->
        lateinit var sources: Map<String, MediaSource>
        onMain {
            val entries = listOf(entry(0), entry(1), entry(2))
            session.update(entries)
            sources = managedSources(session, manager)
            session.update(entries)
            managedSources(session, manager).forEach { (id, source) -> assertSame(sources[id], source) }
            session.release()
            session.release()
            assertTrue(session.state.value.entryIds.isEmpty())
        }
    }

    private fun entry(index: Int, revision: Int = 0) = AMediaQueueEntry(
        entryId = "entry-$index",
        item = AMediaItem(
            mediaId = "item-$index",
            uri = Uri.parse("file:///unused/entry-$index-$revision.mp4"),
        ),
    )

    private fun withSession(block: (AMediaPreloadSession, DefaultPreloadManager) -> Unit) {
        lateinit var session: AMediaPreloadSession
        lateinit var manager: DefaultPreloadManager
        onMain {
            session = AMediaPreloadSessionFactory.create(
                InstrumentationRegistry.getInstrumentation().targetContext,
                AMediaPreloadConfiguration(enabled = false),
            )
            manager = session.javaClass.getDeclaredField("preloadManager").apply {
                isAccessible = true
            }.get(session) as DefaultPreloadManager
        }
        try { block(session, manager) } finally { onMain { session.release() } }
    }

    // Inspect ownership without adding debug-only methods to the public AMedia API.
    private fun managedSources(session: AMediaPreloadSession, manager: DefaultPreloadManager): Map<String, MediaSource> {
        val records = session.javaClass.getDeclaredField("records").apply { isAccessible = true }
            .get(session) as Map<*, *>
        return records.entries.associate { (id, record) ->
            checkNotNull(record)
            val item = record.javaClass.getDeclaredField("mediaItem").apply { isAccessible = true }
                .get(record) as androidx.media3.common.MediaItem
            id as String to checkNotNull(manager.getMediaSource(item))
        }
    }

    private fun assertReleased(source: MediaSource) {
        val handler = source.javaClass.getDeclaredField("preloadHandler").apply { isAccessible = true }
            .get(source) as HandlerWrapper
        val completion = CountDownLatch(1)
        var released = false
        handler.post {
            released = source.javaClass.getDeclaredField("releasePreloadMediaSourceCalled")
                .apply { isAccessible = true }.getBoolean(source)
            completion.countDown()
        }
        assertTrue("preload looper did not drain", completion.await(5, TimeUnit.SECONDS))
        assertTrue("replaced/evicted source must release its preloaded resources", released)
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
