package com.ibabyblue.amedia.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AMediaFeedPolicyTest {
    @Test
    fun unmeteredPolicyBlocksMeteredNetworksButAllowsOfflineLocalMedia() {
        assertFalse(isPreloadingAllowed(AMediaFeedNetworkPreloadPolicy.UnmeteredOnly, true))
        assertTrue(isPreloadingAllowed(AMediaFeedNetworkPreloadPolicy.UnmeteredOnly, false))
        assertTrue(isPreloadingAllowed(AMediaFeedNetworkPreloadPolicy.UnmeteredOnly, null))
    }

    @Test
    fun explicitPoliciesIgnoreNetworkState() {
        assertTrue(isPreloadingAllowed(AMediaFeedNetworkPreloadPolicy.Always, true))
        assertFalse(isPreloadingAllowed(AMediaFeedNetworkPreloadPolicy.Never, false))
    }
}
