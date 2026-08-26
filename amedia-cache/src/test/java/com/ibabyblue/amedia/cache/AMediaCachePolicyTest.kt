package com.ibabyblue.amedia.cache

import org.junit.Assert.assertEquals
import org.junit.Test

class AMediaCachePolicyTest {
    @Test
    fun `clear policies retain their validated limits`() {
        assertEquals(
            128L,
            (AMediaCacheClearPolicy.TrimToBytes(128L)).maxBytes,
        )
        assertEquals(
            456L,
            (AMediaCacheClearPolicy.OlderThan(456L)).cutoffEpochMillis,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `trim limit cannot be negative`() {
        AMediaCacheClearPolicy.TrimToBytes(-1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `space name cannot contain path separators`() {
        AMediaCacheSpace.Named("audio/conversation")
    }

    @Test
    fun `named spaces isolate equal public keys`() {
        val audio = AMediaCacheSpace.Named("audio")
        val video = AMediaCacheSpace.Named("video")

        val audioStorageKey = AMediaCacheKeyCodec.storageKey(audio, "shared")
        val videoStorageKey = AMediaCacheKeyCodec.storageKey(video, "shared")

        assertEquals("shared", AMediaCacheKeyCodec.rawKey(audio, audioStorageKey))
        assertEquals(audio, AMediaCacheKeyCodec.spaceOf(audioStorageKey))
        assertEquals(video, AMediaCacheKeyCodec.spaceOf(videoStorageKey))
        org.junit.Assert.assertNotEquals(audioStorageKey, videoStorageKey)
    }

    @Test
    fun `default space escapes keys that resemble internal namespaces`() {
        val publicKey = "__amedia_space_v1__/looks/internal"
        val storageKey = AMediaCacheKeyCodec.storageKey(AMediaCacheSpace.Default, publicKey)

        assertEquals(AMediaCacheSpace.Default, AMediaCacheKeyCodec.spaceOf(storageKey))
        assertEquals(publicKey, AMediaCacheKeyCodec.rawKey(AMediaCacheSpace.Default, storageKey))
    }
}
