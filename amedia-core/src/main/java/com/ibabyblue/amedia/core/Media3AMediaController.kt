package com.ibabyblue.amedia.core

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class Media3AMediaController(
    internal val player: Player,
    private val configuration: AMediaControllerConfiguration,
    private val releasePlayer: Boolean,
) : AMediaController {
    private val controllerId = UUID.randomUUID().toString()
    private val released = AtomicBoolean(false)
    private val handler = Handler(player.applicationLooper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var generation = 0L
    private var desiredPlayWhenReady = false
    private var unmutedVolume = configuration.initialVolume
    private var muted = configuration.initiallyMuted
    private var pendingSeek = false
    private var lastTransitionEntryId: String? = null

    private val mutableState = MutableStateFlow(
        AMediaState(
            controllerId = controllerId,
            isMuted = muted,
            volume = unmutedVolume,
            speed = configuration.initialSpeed,
            repeatMode = configuration.repeatMode,
            subtitlesEnabled = configuration.subtitlesEnabled,
        ),
    )
    override val state: StateFlow<AMediaState> = mutableState.asStateFlow()

    private val mutableProgress = MutableStateFlow(AMediaProgress())
    override val progress: StateFlow<AMediaProgress> = mutableProgress.asStateFlow()

    private val mutableEvents = MutableSharedFlow<AMediaEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AMediaEvent> = mutableEvents.asSharedFlow()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = syncFromPlayer()

        override fun onIsPlayingChanged(isPlaying: Boolean) = syncFromPlayer()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = syncFromPlayer()

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
            syncFromPlayer()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val entryId = mediaItem?.mediaId
            if (entryId != lastTransitionEntryId) {
                generation += 1L
                lastTransitionEntryId = entryId
                mutableState.value = mutableState.value.copy(
                    generation = generation,
                    isFirstFrameRendered = false,
                    error = null,
                )
                mutableEvents.tryEmit(AMediaEvent.MediaChanged(entryId, generation))
            }
            syncFromPlayer()
        }

        override fun onRenderedFirstFrame() {
            val entryId = currentEntryId() ?: return
            mutableState.value = mutableState.value.copy(isFirstFrameRendered = true)
            mutableEvents.tryEmit(AMediaEvent.FirstFrameRendered(entryId, generation))
        }

        override fun onPlayerError(error: PlaybackException) {
            val publicError = error.toAMediaError()
            mutableState.value = mutableState.value.copy(
                status = AMediaPlaybackStatus.Failed,
                isPlaying = false,
                error = publicError,
            )
            mutableEvents.tryEmit(AMediaEvent.Failed(currentEntryId(), generation, publicError))
            log(AMediaLogLevel.Error, "playback failed entry=${currentEntryId()} generation=$generation", error)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            mutableState.value = mutableState.value.copy(
                videoSize = videoSize
                    .takeIf { it.width > 0 && it.height > 0 }
                    ?.let { AMediaVideoSize(it.width, it.height, it.pixelWidthHeightRatio) },
            )
            syncCapabilities()
        }

        override fun onTracksChanged(tracks: Tracks) {
            mutableState.value = mutableState.value.copy(tracks = tracks.toAMediaTracks())
            syncCapabilities()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (pendingSeek && reason == Player.DISCONTINUITY_REASON_SEEK) {
                pendingSeek = false
                updateProgress()
                currentEntryId()?.let {
                    mutableEvents.tryEmit(
                        AMediaEvent.SeekCompleted(it, generation, newPosition.positionMs),
                    )
                }
            }
            syncFromPlayer()
        }

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = syncCapabilities()
    }

    init {
        onPlayerThread {
            player.addListener(listener)
            player.volume = if (muted) 0f else unmutedVolume
            player.setPlaybackSpeed(configuration.initialSpeed)
            player.repeatMode = configuration.repeatMode.toMedia3RepeatMode()
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !configuration.subtitlesEnabled)
                .build()
            syncFromPlayer()
        }
        scope.launch {
            mutableProgress.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed ->
                    while (observed && isActive && !released.get()) {
                        onPlayerThread(::updateProgress)
                        delay(configuration.progressIntervalMillis)
                    }
                }
        }
    }

    override fun setMedia(
        item: AMediaItem,
        startPositionMillis: Long,
        playWhenReady: Boolean?,
    ): AMediaCommandResult = setQueue(
        entries = listOf(AMediaQueueEntry(item.mediaId, item, startPositionMillis)),
        startIndex = 0,
        startPositionMillis = startPositionMillis,
        playWhenReady = playWhenReady,
    )

    override fun setQueue(
        entries: List<AMediaQueueEntry>,
        startIndex: Int,
        startPositionMillis: Long?,
        playWhenReady: Boolean?,
    ): AMediaCommandResult {
        if (entries.isEmpty()) return clear()
        validateQueueRequest(
            entryIds = entries.map(AMediaQueueEntry::entryId),
            entryStartPositions = entries.map(AMediaQueueEntry::startPositionMillis),
            startIndex = startIndex,
            startPositionMillis = startPositionMillis,
        )?.let { rejection ->
            return AMediaCommandResult.Rejected(rejection)
        }
        return enqueue {
            generation += 1L
            desiredPlayWhenReady = playWhenReady ?: desiredPlayWhenReady
            lastTransitionEntryId = entries[startIndex].entryId
            val position = startPositionMillis ?: entries[startIndex].startPositionMillis
            mutableState.value = mutableState.value.copy(
                generation = generation,
                status = AMediaPlaybackStatus.Preparing,
                queue = entries.toList(),
                currentIndex = startIndex,
                playWhenReady = desiredPlayWhenReady,
                isPlaying = false,
                isFirstFrameRendered = false,
                error = null,
                videoSize = null,
                tracks = emptyList(),
            )
            mutableProgress.value = AMediaProgress(
                entryId = entries[startIndex].entryId,
                positionMillis = position,
            )
            player.setMediaItems(entries.map(AMediaQueueEntry::toMedia3Item), startIndex, position)
            applyEffectivePlayWhenReady()
            player.prepare()
            mutableEvents.tryEmit(AMediaEvent.MediaChanged(entries[startIndex].entryId, generation))
            log(AMediaLogLevel.Debug, "queue set size=${entries.size} generation=$generation", null)
        }
    }

    override fun add(entry: AMediaQueueEntry, index: Int): AMediaCommandResult {
        val snapshot = state.value
        if (snapshot.queue.any { it.entryId == entry.entryId }) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.DuplicateQueueEntryId)
        }
        if (index !in 0..snapshot.queue.size) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.InvalidArgument)
        }
        return enqueue {
            val queue = mutableState.value.queue.toMutableList().apply { add(index, entry) }
            mutableState.value = mutableState.value.copy(
                queue = queue,
                currentIndex = player.currentMediaItemIndex,
            )
            player.addMediaItem(index, entry.toMedia3Item())
            syncCapabilities()
        }
    }

    override fun move(entryId: String, toIndex: Int): AMediaCommandResult {
        val fromIndex = state.value.queue.indexOfFirst { it.entryId == entryId }
        if (fromIndex == -1) return AMediaCommandResult.Rejected(AMediaCommandRejection.EntryNotFound)
        if (toIndex !in state.value.queue.indices) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.InvalidArgument)
        }
        return enqueue {
            val queue = mutableState.value.queue.toMutableList()
            val entry = queue.removeAt(queue.indexOfFirst { it.entryId == entryId })
            queue.add(toIndex, entry)
            mutableState.value = mutableState.value.copy(queue = queue)
            player.moveMediaItem(fromIndex, toIndex)
            syncFromPlayer()
        }
    }

    override fun remove(entryId: String): AMediaCommandResult {
        val index = state.value.queue.indexOfFirst { it.entryId == entryId }
        if (index == -1) return AMediaCommandResult.Rejected(AMediaCommandRejection.EntryNotFound)
        return enqueue {
            val queue = mutableState.value.queue.toMutableList()
            val actualIndex = queue.indexOfFirst { it.entryId == entryId }
            if (actualIndex == -1) return@enqueue
            queue.removeAt(actualIndex)
            player.removeMediaItem(actualIndex)
            if (queue.isEmpty()) {
                clearOnPlayerThread()
            } else {
                mutableState.value = mutableState.value.copy(
                    queue = queue,
                    currentIndex = player.currentMediaItemIndex.coerceIn(queue.indices),
                )
                syncFromPlayer()
            }
        }
    }

    override fun prepare(): AMediaCommandResult {
        if (state.value.queue.isEmpty()) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.EmptyQueue)
        }
        return enqueue {
            mutableState.value = mutableState.value.copy(
                status = AMediaPlaybackStatus.Preparing,
                error = null,
            )
            player.prepare()
        }
    }

    override fun play(): AMediaCommandResult = enqueue {
        desiredPlayWhenReady = true
        mutableState.value = mutableState.value.copy(playWhenReady = true)
        applyEffectivePlayWhenReady()
    }

    override fun pause(): AMediaCommandResult = enqueue {
        desiredPlayWhenReady = false
        player.playWhenReady = false
        mutableState.value = mutableState.value.copy(
            playWhenReady = false,
            status = if (mutableState.value.queue.isEmpty()) {
                AMediaPlaybackStatus.Idle
            } else {
                AMediaPlaybackStatus.Paused
            },
            isPlaying = false,
        )
    }

    override fun stop(): AMediaCommandResult {
        if (state.value.queue.isEmpty()) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.EmptyQueue)
        }
        return enqueue {
            desiredPlayWhenReady = false
            player.playWhenReady = false
            pendingSeek = true
            val index = player.currentMediaItemIndex.coerceAtLeast(0)
            val target = mutableState.value.queue.getOrNull(index)?.startPositionMillis ?: 0L
            mutableProgress.value = mutableProgress.value.copy(isSeeking = true)
            player.seekTo(index, target)
            mutableState.value = mutableState.value.copy(
                playWhenReady = false,
                status = AMediaPlaybackStatus.Paused,
                isPlaying = false,
            )
        }
    }

    override fun clear(): AMediaCommandResult = enqueue(::clearOnPlayerThread)

    override fun retry(): AMediaCommandResult {
        if (state.value.queue.isEmpty()) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.EmptyQueue)
        }
        return enqueue {
            mutableState.value = mutableState.value.copy(
                status = AMediaPlaybackStatus.Preparing,
                error = null,
                isFirstFrameRendered = false,
            )
            player.prepare()
            applyEffectivePlayWhenReady()
        }
    }

    override fun seekTo(positionMillis: Long): AMediaCommandResult {
        if (positionMillis < 0L) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.InvalidArgument)
        }
        if (!state.value.capabilities.canSeek) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.Unsupported)
        }
        return enqueue {
            pendingSeek = true
            mutableProgress.value = mutableProgress.value.copy(isSeeking = true)
            player.seekTo(positionMillis)
        }
    }

    override fun seekToEntry(entryId: String, positionMillis: Long): AMediaCommandResult {
        val index = state.value.queue.indexOfFirst { it.entryId == entryId }
        if (index == -1) return AMediaCommandResult.Rejected(AMediaCommandRejection.EntryNotFound)
        if (positionMillis < 0L) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.InvalidArgument)
        }
        return enqueue {
            pendingSeek = true
            mutableProgress.value = mutableProgress.value.copy(isSeeking = true)
            player.seekTo(index, positionMillis)
        }
    }

    override fun skipToPrevious(): AMediaCommandResult {
        if (!state.value.capabilities.canSkipPrevious) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.Unsupported)
        }
        return enqueue { player.seekToPreviousMediaItem() }
    }

    override fun skipToNext(): AMediaCommandResult {
        if (!state.value.capabilities.canSkipNext) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.Unsupported)
        }
        return enqueue { player.seekToNextMediaItem() }
    }

    override fun setMuted(muted: Boolean): AMediaCommandResult = enqueue {
        this.muted = muted
        player.volume = if (muted) 0f else unmutedVolume
        mutableState.value = mutableState.value.copy(isMuted = muted, volume = unmutedVolume)
    }

    override fun setVolume(volume: Float): AMediaCommandResult {
        if (volume !in 0f..1f) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.InvalidArgument)
        }
        return enqueue {
            unmutedVolume = volume
            if (!muted) player.volume = volume
            mutableState.value = mutableState.value.copy(volume = volume)
        }
    }

    override fun setSpeed(speed: Float): AMediaCommandResult {
        if (speed <= 0f) return AMediaCommandResult.Rejected(AMediaCommandRejection.InvalidArgument)
        return enqueue {
            player.setPlaybackSpeed(speed)
            mutableState.value = mutableState.value.copy(speed = speed)
        }
    }

    override fun setRepeatMode(repeatMode: AMediaRepeatMode): AMediaCommandResult = enqueue {
        player.repeatMode = repeatMode.toMedia3RepeatMode()
        mutableState.value = mutableState.value.copy(repeatMode = repeatMode)
    }

    override fun setSubtitlesEnabled(enabled: Boolean): AMediaCommandResult = enqueue {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
        mutableState.value = mutableState.value.copy(
            subtitlesEnabled = enabled,
            selectedSubtitleTrackId = if (enabled) {
                mutableState.value.selectedSubtitleTrackId
            } else {
                null
            },
        )
    }

    override fun selectTrack(trackId: String?): AMediaCommandResult {
        if (trackId == null) return setSubtitlesEnabled(false)
        val selection = findTrack(trackId)
            ?: return AMediaCommandResult.Rejected(AMediaCommandRejection.EntryNotFound)
        return enqueue {
            val (group, trackIndex) = selection
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(group.type, false)
                .clearOverridesOfType(group.type)
                .addOverride(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                .build()
            mutableState.value = mutableState.value.copy(
                subtitlesEnabled = true,
                selectedSubtitleTrackId = trackId.takeIf { group.type == C.TRACK_TYPE_TEXT },
            )
        }
    }

    override fun addSuppression(reason: AMediaSuppressionReason): AMediaCommandResult = enqueue {
        mutableState.value = mutableState.value.copy(
            suppressionReasons = mutableState.value.suppressionReasons + reason,
        )
        applyEffectivePlayWhenReady()
    }

    override fun removeSuppression(reason: AMediaSuppressionReason): AMediaCommandResult = enqueue {
        mutableState.value = mutableState.value.copy(
            suppressionReasons = mutableState.value.suppressionReasons - reason,
        )
        applyEffectivePlayWhenReady()
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        val releaseAction = {
            player.removeListener(listener)
            player.clearVideoSurface()
            if (releasePlayer) player.release()
            mutableState.value = mutableState.value.copy(
                status = AMediaPlaybackStatus.Idle,
                queue = emptyList(),
                currentIndex = -1,
                playWhenReady = false,
                isPlaying = false,
                isFirstFrameRendered = false,
                tracks = emptyList(),
                videoSize = null,
            )
            mutableProgress.value = AMediaProgress()
            scope.cancel()
        }
        if (Looper.myLooper() == handler.looper) releaseAction() else handler.post(releaseAction)
    }

    private fun enqueue(action: () -> Unit): AMediaCommandResult {
        if (released.get()) return AMediaCommandResult.Rejected(AMediaCommandRejection.Released)
        onPlayerThread {
            if (!released.get()) action()
        }
        return AMediaCommandResult.Accepted
    }

    private fun onPlayerThread(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post(action)
    }

    private fun clearOnPlayerThread() {
        desiredPlayWhenReady = false
        generation += 1L
        lastTransitionEntryId = null
        player.playWhenReady = false
        player.clearMediaItems()
        mutableState.value = mutableState.value.copy(
            generation = generation,
            status = AMediaPlaybackStatus.Idle,
            queue = emptyList(),
            currentIndex = -1,
            playWhenReady = false,
            isPlaying = false,
            isFirstFrameRendered = false,
            error = null,
            tracks = emptyList(),
            videoSize = null,
            capabilities = AMediaCapabilities(),
        )
        mutableProgress.value = AMediaProgress()
        mutableEvents.tryEmit(AMediaEvent.MediaChanged(null, generation))
    }

    private fun applyEffectivePlayWhenReady() {
        player.playWhenReady = desiredPlayWhenReady && mutableState.value.suppressionReasons.isEmpty()
        syncFromPlayer()
    }

    private fun syncFromPlayer() {
        if (released.get()) return
        val status = when (player.playbackState) {
            Player.STATE_IDLE -> if (mutableState.value.queue.isEmpty()) {
                AMediaPlaybackStatus.Idle
            } else {
                AMediaPlaybackStatus.Preparing
            }
            Player.STATE_BUFFERING -> AMediaPlaybackStatus.Buffering
            Player.STATE_READY -> when {
                player.isPlaying -> AMediaPlaybackStatus.Playing
                desiredPlayWhenReady && mutableState.value.suppressionReasons.isEmpty() ->
                    AMediaPlaybackStatus.Ready
                else -> AMediaPlaybackStatus.Paused
            }
            Player.STATE_ENDED -> AMediaPlaybackStatus.Ended
            else -> AMediaPlaybackStatus.Idle
        }
        val index = player.currentMediaItemIndex
        mutableState.value = mutableState.value.copy(
            status = status,
            currentIndex = index.takeIf { it in mutableState.value.queue.indices } ?: -1,
            playWhenReady = desiredPlayWhenReady,
            isPlaying = player.isPlaying,
            error = if (status == AMediaPlaybackStatus.Failed) mutableState.value.error else null,
        )
        if (status == AMediaPlaybackStatus.Ended) {
            currentEntryId()?.let {
                mutableEvents.tryEmit(AMediaEvent.PlaybackEnded(it, generation))
            }
        }
        updateProgress()
        syncCapabilities()
    }

    private fun syncCapabilities() {
        if (released.get()) return
        val commands = player.availableCommands
        val queue = mutableState.value.queue
        mutableState.value = mutableState.value.copy(
            capabilities = AMediaCapabilities(
                canPlay = commands.contains(Player.COMMAND_PLAY_PAUSE),
                canPause = commands.contains(Player.COMMAND_PLAY_PAUSE),
                canSeek = commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
                canSkipPrevious = commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM),
                canSkipNext = commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM),
                canSetSpeed = commands.contains(Player.COMMAND_SET_SPEED_AND_PITCH),
                canSetTracks = mutableState.value.tracks.isNotEmpty(),
                hasVideoOutput = mutableState.value.videoSize != null ||
                    queue.any { it.item.metadata.contentType == AMediaContentType.Video },
            ),
        )
    }

    private fun updateProgress() {
        if (released.get()) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it >= 0L }
        mutableProgress.value = AMediaProgress(
            entryId = currentEntryId(),
            positionMillis = player.currentPosition.coerceAtLeast(0L),
            durationMillis = duration,
            bufferedPositionMillis = player.bufferedPosition.coerceAtLeast(0L),
            isSeeking = pendingSeek,
        )
    }

    private fun currentEntryId(): String? = player.currentMediaItem?.mediaId

    private fun findTrack(trackId: String): Pair<Tracks.Group, Int>? {
        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                if (trackIdentity(groupIndex, trackIndex, group.getTrackFormat(trackIndex)) == trackId) {
                    return group to trackIndex
                }
            }
        }
        return null
    }

    private fun log(level: AMediaLogLevel, message: String, throwable: Throwable?) {
        configuration.logger.log(level, "controller=$controllerId $message", throwable)
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun AMediaQueueEntry.toMedia3Item(): MediaItem {
    val source = item
    val builder = MediaItem.Builder()
        .setMediaId(entryId)
        .setUri(source.uri)
        .setMimeType(source.mimeType)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(source.metadata.title)
                .setArtist(source.metadata.artist)
                .setAlbumTitle(source.metadata.albumTitle)
                .setArtworkUri(source.metadata.artworkUri)
                .build(),
        )

    source.http.cacheKey?.let(builder::setCustomCacheKey)
    if (source.subtitles.isNotEmpty()) {
        builder.setSubtitleConfigurations(source.subtitles.map(AMediaSubtitle::toMedia3Subtitle))
    }
    source.drm?.let { drm ->
        val drmBuilder = MediaItem.DrmConfiguration.Builder(drm.scheme)
            .setLicenseRequestHeaders(drm.requestHeaders)
            .setMultiSession(drm.multiSession)
            .setForceDefaultLicenseUri(drm.forceDefaultLicenseUri)
        drm.licenseUri?.let(drmBuilder::setLicenseUri)
        drm.offlineKeySetId?.let(drmBuilder::setKeySetId)
        builder.setDrmConfiguration(drmBuilder.build())
    }
    source.clipping?.let { clipping ->
        val clippingBuilder = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(clipping.startPositionMillis)
        clipping.endPositionMillis?.let(clippingBuilder::setEndPositionMs)
        builder.setClippingConfiguration(clippingBuilder.build())
    }
    return builder.build()
}

private fun AMediaSubtitle.toMedia3Subtitle(): MediaItem.SubtitleConfiguration {
    var selectionFlags = 0
    if (isDefault) selectionFlags = selectionFlags or C.SELECTION_FLAG_DEFAULT
    if (isForced) selectionFlags = selectionFlags or C.SELECTION_FLAG_FORCED
    return MediaItem.SubtitleConfiguration.Builder(uri)
        .setId(id)
        .setMimeType(mimeType)
        .setLanguage(language)
        .setLabel(label)
        .setSelectionFlags(selectionFlags)
        .build()
}

private fun AMediaRepeatMode.toMedia3RepeatMode(): Int = when (this) {
    AMediaRepeatMode.Off -> Player.REPEAT_MODE_OFF
    AMediaRepeatMode.One -> Player.REPEAT_MODE_ONE
    AMediaRepeatMode.All -> Player.REPEAT_MODE_ALL
}

private fun Tracks.toAMediaTracks(): List<AMediaTrack> = buildList {
    groups.forEachIndexed { groupIndex, group ->
        for (trackIndex in 0 until group.length) {
            val format = group.getTrackFormat(trackIndex)
            add(
                AMediaTrack(
                    id = trackIdentity(groupIndex, trackIndex, format),
                    type = group.type.toAMediaTrackType(),
                    label = format.label,
                    language = format.language,
                    mimeType = format.sampleMimeType,
                    selected = group.isTrackSelected(trackIndex),
                    supported = group.isTrackSupported(trackIndex),
                ),
            )
        }
    }
}

private fun trackIdentity(groupIndex: Int, trackIndex: Int, format: Format): String =
    "$groupIndex:$trackIndex:${format.id.orEmpty()}"

private fun Int.toAMediaTrackType(): AMediaTrackType = when (this) {
    C.TRACK_TYPE_AUDIO -> AMediaTrackType.Audio
    C.TRACK_TYPE_VIDEO -> AMediaTrackType.Video
    C.TRACK_TYPE_TEXT -> AMediaTrackType.Text
    else -> AMediaTrackType.Unknown
}

private fun PlaybackException.toAMediaError(): AMediaError {
    val category = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        -> AMediaErrorCategory.Network
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
        -> AMediaErrorCategory.Drm
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        -> AMediaErrorCategory.Decoder
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        -> AMediaErrorCategory.Source
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> AMediaErrorCategory.Unsupported
        else -> AMediaErrorCategory.Unexpected
    }
    return AMediaError(
        category = category,
        message = message ?: errorCodeName,
        isRecoverable = category == AMediaErrorCategory.Network,
        cause = this,
    )
}
