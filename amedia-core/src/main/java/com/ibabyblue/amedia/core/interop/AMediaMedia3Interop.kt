@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package com.ibabyblue.amedia.core.interop

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource
import com.ibabyblue.amedia.core.AMediaAudioAmplitudeConfiguration
import com.ibabyblue.amedia.core.AMediaAudioAmplitudeRenderersFactory
import com.ibabyblue.amedia.core.AMediaBackgroundPlaybackPolicy
import com.ibabyblue.amedia.core.AMediaCommandResult
import com.ibabyblue.amedia.core.AMediaContentType
import com.ibabyblue.amedia.core.AMediaController
import com.ibabyblue.amedia.core.AMediaControllerConfiguration
import com.ibabyblue.amedia.core.AMediaQueueEntry
import com.ibabyblue.amedia.core.ExperimentalAMediaApi
import com.ibabyblue.amedia.core.Media3AMediaController
import com.ibabyblue.amedia.core.audioAmplitudeRuntime
import com.ibabyblue.amedia.core.createDefaultMediaSource
import com.ibabyblue.amedia.core.toMedia3Item
import com.ibabyblue.amedia.core.toAMediaBackgroundPlaybackPolicy
import com.ibabyblue.amedia.core.toAMediaContentType

/** Escape hatch for integrations that must own or inspect the underlying Media3 player. */
@ExperimentalAMediaApi
public object AMediaMedia3Interop {
    @JvmStatic
    public fun wrap(
        player: Player,
        configuration: AMediaControllerConfiguration = AMediaControllerConfiguration(),
        releasePlayer: Boolean = false,
        mediaSourceProvider: ((AMediaQueueEntry) -> MediaSource)? = null,
    ): AMediaController = wrapInternal(
        player,
        configuration,
        releasePlayer,
        mediaSourceProvider,
        audioAmplitudeRenderersFactory = null,
    )

    @JvmStatic
    public fun wrap(
        player: Player,
        configuration: AMediaControllerConfiguration = AMediaControllerConfiguration(),
        releasePlayer: Boolean = false,
        mediaSourceProvider: ((AMediaQueueEntry) -> MediaSource)? = null,
        audioAmplitudeRenderersFactory: RenderersFactory?,
    ): AMediaController = wrapInternal(
        player,
        configuration,
        releasePlayer,
        mediaSourceProvider,
        audioAmplitudeRenderersFactory,
    )

    private fun wrapInternal(
        player: Player,
        configuration: AMediaControllerConfiguration,
        releasePlayer: Boolean,
        mediaSourceProvider: ((AMediaQueueEntry) -> MediaSource)?,
        audioAmplitudeRenderersFactory: RenderersFactory?,
    ): AMediaController = Media3AMediaController(
        player,
        configuration,
        releasePlayer,
        audioAmplitudeRuntime(audioAmplitudeRenderersFactory),
        mediaSourceProvider,
    )

    /** Creates the Media3 renderer seam required for opt-in decoded PCM amplitude levels. */
    @JvmStatic
    public fun audioAmplitudeRenderersFactory(
        context: Context,
        configuration: AMediaAudioAmplitudeConfiguration,
    ): RenderersFactory = AMediaAudioAmplitudeRenderersFactory(
        context.applicationContext,
        configuration,
    )

    @JvmStatic
    public fun player(controller: AMediaController): Player? =
        (controller as? Media3AMediaController)?.player

    @JvmStatic
    public fun mediaItem(entry: AMediaQueueEntry): MediaItem = entry.toMedia3Item()

    @JvmStatic
    public fun backgroundPlaybackPolicy(mediaItem: MediaItem): AMediaBackgroundPlaybackPolicy =
        mediaItem.toAMediaBackgroundPlaybackPolicy()

    @JvmStatic
    public fun contentType(mediaItem: MediaItem): AMediaContentType =
        mediaItem.toAMediaContentType()

    @JvmStatic
    @JvmOverloads
    public fun mediaSource(
        context: Context,
        entry: AMediaQueueEntry,
        userAgent: String = "AMedia/0.1",
    ): MediaSource = createDefaultMediaSource(context, entry, userAgent)

    @JvmStatic
    public fun setPreloadedMedia(
        controller: AMediaController,
        entry: AMediaQueueEntry,
        mediaSource: MediaSource,
        startPositionMillis: Long = 0L,
        playWhenReady: Boolean = true,
    ): AMediaCommandResult = (controller as? Media3AMediaController)
        ?.setPreloadedMedia(entry, mediaSource, startPositionMillis, playWhenReady)
        ?: AMediaCommandResult.Rejected(
            com.ibabyblue.amedia.core.AMediaCommandRejection.Unsupported,
        )
}
