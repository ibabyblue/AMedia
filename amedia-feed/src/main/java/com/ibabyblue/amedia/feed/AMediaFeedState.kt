package com.ibabyblue.amedia.feed

import com.ibabyblue.amedia.core.AMediaQueueEntry

public data class AMediaFeedState(
    public val entries: List<AMediaQueueEntry> = emptyList(),
    public val activeIndex: Int = -1,
    public val activeEntryId: String? = null,
    public val preloadingEnabled: Boolean = true,
    public val preloadingRequested: Boolean = true,
    public val networkAllowsPreloading: Boolean = true,
)
