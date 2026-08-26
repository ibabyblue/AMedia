@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ibabyblue.amedia.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.net.Uri
import com.ibabyblue.amedia.core.AMediaCachePolicy
import com.ibabyblue.amedia.core.AMediaItem
import com.ibabyblue.amedia.core.AMediaQueueEntry
import com.ibabyblue.amedia.core.ExperimentalAMediaApi
import java.io.FileOutputStream
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AMediaCacheManagerTest {
    private lateinit var manager: AMediaCacheManager
    private val openedCaches = mutableListOf<AMediaCache>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = AMediaCacheManagerFactory.create(
            context,
            AMediaCacheConfiguration(
                directoryName = "amedia-manager-test-${UUID.randomUUID()}",
                maxBytes = 1_024L,
            ),
        )
    }

    @After
    fun tearDown() {
        val directory = manager.directory
        openedCaches.asReversed().forEach(AMediaCache::close)
        manager.close()
        directory.deleteRecursively()
    }

    @Test
    fun spacesIsolateEqualPublicKeysAndCanBeClearedIndependently() {
        val audioSpace = AMediaCacheSpace.Named("audio")
        val videoSpace = AMediaCacheSpace.Named("video")
        val audio = open(audioSpace)
        val video = open(videoSpace)

        write(audio, "shared", 7)
        write(video, "shared", 11)

        assertEquals(setOf("shared"), audio.keys)
        assertEquals(setOf("shared"), video.keys)
        assertEquals(7L, audio.cachedBytes("shared"))
        assertEquals(11L, video.cachedBytes("shared"))
        val snapshot = manager.snapshot()
        assertEquals(18L, snapshot.sizeBytes)
        assertEquals(2, snapshot.spaces.size)
        assertTrue(snapshot.spaces.all { it.resourceCount == 1 })

        val removed = manager.clear(
            AMediaCacheScope.Spaces(setOf(audioSpace)),
            AMediaCacheClearPolicy.All,
        )

        assertEquals(7L, removed)
        assertEquals(0L, audio.cachedBytes("shared"))
        assertEquals(11L, video.cachedBytes("shared"))
    }

    @Test
    fun batchRemovalReportsRemovedBytesAndLeavesOtherResources() {
        val cache = open(AMediaCacheSpace.Named("speech"))
        write(cache, "one", 3)
        write(cache, "two", 5)
        write(cache, "three", 7)

        val removed = cache.remove(listOf("one", "three", "one"))

        assertEquals(10L, removed)
        assertEquals(setOf("two"), cache.keys)
        assertEquals(5L, cache.sizeBytes)
    }

    @Test
    fun managerTrimAppliesOneLruTargetAcrossSelectedSpaces() {
        val audio = open(AMediaCacheSpace.Named("audio"))
        val video = open(AMediaCacheSpace.Named("video"))
        write(audio, "old", 6)
        write(video, "new", 8)

        val removed = manager.clear(policy = AMediaCacheClearPolicy.TrimToBytes(8L))

        assertTrue(removed >= 6L)
        assertTrue(manager.snapshot().sizeBytes <= 8L)
    }

    @Test
    fun automaticLruEnforcesOneGlobalStoreLimitAcrossSpaces() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val smallManager = AMediaCacheManagerFactory.create(
            context,
            AMediaCacheConfiguration(
                directoryName = "amedia-small-test-${UUID.randomUUID()}",
                maxBytes = 10L,
            ),
        )
        val audio = smallManager.open(AMediaCacheSpace.Named("audio"))
        val video = smallManager.open(AMediaCacheSpace.Named("video"))
        val directory = smallManager.directory
        try {
            write(audio, "old", 6)
            write(video, "new", 6)

            assertTrue(smallManager.snapshot().sizeBytes <= 10L)
            assertEquals(6L, video.cachedBytes("new"))
        } finally {
            video.close()
            audio.close()
            smallManager.close()
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalAMediaApi::class)
    @Test
    fun mediaSourcesUseSpaceSpecificStorageKeys() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = open(AMediaCacheSpace.Named("audio"))
        val video = open(AMediaCacheSpace.Named("video"))
        val entry = AMediaQueueEntry(
            entryId = "entry",
            item = AMediaItem(
                mediaId = "media",
                uri = Uri.parse("https://example.com/media.mp3"),
                cachePolicy = AMediaCachePolicy.ReadThrough,
            ),
        )

        val audioKey = AMediaCacheMedia3Interop.mediaSource(context, audio, entry)
            .mediaItem.localConfiguration?.customCacheKey
        val videoKey = AMediaCacheMedia3Interop.mediaSource(context, video, entry)
            .mediaItem.localConfiguration?.customCacheKey

        assertNotEquals(audioKey, videoKey)
    }

    @Test
    fun olderThanRemovesMatchingSpans() {
        val cache = open(AMediaCacheSpace.Named("temporary"))
        write(cache, "expired", 9)

        val removed = cache.clear(AMediaCacheClearPolicy.OlderThan(Long.MAX_VALUE))

        assertEquals(9L, removed)
        assertEquals(0L, cache.sizeBytes)
    }

    @Test
    fun openedCacheHandleOutlivesManagerHandle() {
        val cache = open(AMediaCacheSpace.Named("audio"))
        write(cache, "voice", 4)

        manager.close()

        assertEquals(4L, cache.cachedBytes("voice"))
    }

    private fun open(space: AMediaCacheSpace): AMediaCache = manager.open(space).also(openedCaches::add)

    private fun write(cache: AMediaCache, publicKey: String, size: Int) {
        val storageKey = cache.storageKey(publicKey)
        val holeSpan = cache.cache.startReadWrite(storageKey, 0L, size.toLong())
        val file = cache.cache.startFile(storageKey, 0L, size.toLong())
        try {
            FileOutputStream(file).use { output -> output.write(ByteArray(size)) }
            cache.cache.commitFile(file, size.toLong())
        } finally {
            cache.cache.releaseHoleSpan(holeSpan)
        }
    }
}
