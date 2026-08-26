package com.ibabyblue.amedia.feed

import com.ibabyblue.amedia.core.AMediaCommandResult
import com.ibabyblue.amedia.core.AMediaController
import com.ibabyblue.amedia.core.AMediaQueueEntry
import com.ibabyblue.amedia.core.AMediaSuppressionReason
import kotlinx.coroutines.flow.StateFlow

public interface AMediaFeedController : AutoCloseable {
    public val playback: AMediaController
    public val state: StateFlow<AMediaFeedState>

    public fun submit(entries: List<AMediaQueueEntry>): AMediaCommandResult
    public fun activate(index: Int): AMediaCommandResult
    public fun deactivate(): AMediaCommandResult
    public fun setPreloadingEnabled(enabled: Boolean): AMediaCommandResult
    public fun addSuppression(reason: AMediaSuppressionReason): AMediaCommandResult
    public fun removeSuppression(reason: AMediaSuppressionReason): AMediaCommandResult
    public fun release()

    override fun close(): Unit = release()
}
