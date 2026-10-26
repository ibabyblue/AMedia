@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ibabyblue.amedia.preload

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import com.ibabyblue.amedia.cache.AMediaCache
import com.ibabyblue.amedia.cache.AMediaCacheMedia3Interop
import com.ibabyblue.amedia.core.AMediaAudioPolicy
import com.ibabyblue.amedia.core.AMediaCommandRejection
import com.ibabyblue.amedia.core.AMediaCommandResult
import com.ibabyblue.amedia.core.AMediaController
import com.ibabyblue.amedia.core.AMediaControllerConfiguration
import com.ibabyblue.amedia.core.AMediaQueueEntry
import com.ibabyblue.amedia.core.ExperimentalAMediaApi
import com.ibabyblue.amedia.core.interop.AMediaMedia3Interop
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public object AMediaPreloadSessionFactory {
    @JvmStatic
    @JvmOverloads
    public fun create(
        context: Context,
        configuration: AMediaPreloadConfiguration = AMediaPreloadConfiguration(),
        controllerConfiguration: AMediaControllerConfiguration = AMediaControllerConfiguration(),
    ): AMediaPreloadSession = createInternal(
        context,
        cache = null,
        configuration,
        controllerConfiguration,
    )

    @JvmStatic
    @JvmOverloads
    public fun create(
        context: Context,
        cache: AMediaCache,
        configuration: AMediaPreloadConfiguration = AMediaPreloadConfiguration(),
        controllerConfiguration: AMediaControllerConfiguration = AMediaControllerConfiguration(),
    ): AMediaPreloadSession = createInternal(
        context,
        cache,
        configuration,
        controllerConfiguration,
    )

    @OptIn(ExperimentalAMediaApi::class)
    private fun createInternal(
        context: Context,
        cache: AMediaCache?,
        configuration: AMediaPreloadConfiguration,
        controllerConfiguration: AMediaControllerConfiguration,
    ): AMediaPreloadSession {
        val appContext = context.applicationContext
        val cacheLease = cache?.let(AMediaCacheMedia3Interop::retain)
        try {
            val targetControl = OrderedTargetPreloadStatusControl(configuration)
            val loadControl = DefaultLoadControl.Builder()
                .setPlayerTargetBufferBytes(
                    PlayerId.PRELOAD.name,
                    configuration.maxPreloadBufferBytes,
                )
                .build()
            val builder = DefaultPreloadManager.Builder(appContext, targetControl)
                .setLoadControl(loadControl)
            val handlesAudioFocus = controllerConfiguration.audioPolicy == AMediaAudioPolicy.Media
            val exoPlayerBuilder = ExoPlayer.Builder(appContext)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(
                            if (controllerConfiguration.audioPolicy == AMediaAudioPolicy.Ambient) {
                                C.AUDIO_CONTENT_TYPE_MUSIC
                            } else {
                                C.AUDIO_CONTENT_TYPE_UNKNOWN
                            },
                        )
                        .build(),
                    handlesAudioFocus,
                )
                .setHandleAudioBecomingNoisy(
                    handlesAudioFocus && controllerConfiguration.handleAudioBecomingNoisy,
                )
            val player = builder.buildExoPlayer(exoPlayerBuilder)
            val preloadManager = builder.build()
            val mediaSourceProvider: (AMediaQueueEntry) -> MediaSource = { entry ->
                if (cacheLease == null) {
                    AMediaMedia3Interop.mediaSource(
                        appContext,
                        entry,
                        configuration.userAgent,
                    )
                } else {
                    AMediaCacheMedia3Interop.mediaSource(
                        appContext,
                        cacheLease,
                        entry,
                        configuration.userAgent,
                    )
                }
            }
            val playback = AMediaMedia3Interop.wrap(
                player = player,
                configuration = controllerConfiguration,
                releasePlayer = false,
                mediaSourceProvider = mediaSourceProvider,
            )
            return DefaultAMediaPreloadSession(
                player,
                preloadManager,
                targetControl,
                playback,
                cacheLease,
                mediaSourceProvider,
                configuration.enabled,
            )
        } catch (throwable: Throwable) {
            cacheLease?.close()
            throw throwable
        }
    }
}

internal class OrderedTargetPreloadStatusControl(
    private val configuration: AMediaPreloadConfiguration,
) : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
    var anchorIndex: Int = C.INDEX_UNSET
    var enabled: Boolean = configuration.enabled

    override fun getTargetPreloadStatus(index: Int): DefaultPreloadManager.PreloadStatus {
        if (!enabled || anchorIndex == C.INDEX_UNSET) {
            return DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        }
        return when (val distance = index - anchorIndex) {
            1 -> loadedOrTracks(configuration.forwardLoadedDurationMillis)
            -1 -> loadedOrTracks(configuration.backwardLoadedDurationMillis)
            else -> if (distance != 0 && abs(distance) <= configuration.trackSelectionDistance) {
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
            } else {
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            }
        }
    }

    private fun loadedOrTracks(durationMillis: Long): DefaultPreloadManager.PreloadStatus =
        if (durationMillis > 0L) {
            DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(durationMillis)
        } else {
            DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
        }
}

@OptIn(ExperimentalAMediaApi::class)
private class DefaultAMediaPreloadSession(
    private val player: ExoPlayer,
    private val preloadManager: DefaultPreloadManager,
    private val targetControl: OrderedTargetPreloadStatusControl,
    override val playback: AMediaController,
    private val cacheLease: AMediaCache?,
    private val mediaSourceProvider: (AMediaQueueEntry) -> MediaSource,
    initiallyEnabled: Boolean,
) : AMediaPreloadSession {
    private data class Record(
        val index: Int,
        val entry: AMediaQueueEntry,
        val mediaItem: MediaItem,
        val source: MediaSource,
    )

    private val released = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(
        AMediaPreloadState(preloadingEnabled = initiallyEnabled),
    )
    override val state: StateFlow<AMediaPreloadState> = mutableState.asStateFlow()
    @Volatile
    private var acceptedEntryIds: Set<String> = emptySet()
    private var records = LinkedHashMap<String, Record>()

    override fun update(entries: List<AMediaQueueEntry>): AMediaCommandResult {
        if (entries.map(AMediaQueueEntry::entryId).toSet().size != entries.size) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.DuplicateQueueEntryId)
        }
        if (released.get()) return AMediaCommandResult.Rejected(AMediaCommandRejection.Released)
        acceptedEntryIds = entries.mapTo(linkedSetOf(), AMediaQueueEntry::entryId)
        return runOnPlayerThread {
            val previous = records
            val next = LinkedHashMap<String, Record>(entries.size)
            entries.forEachIndexed { index, entry ->
                val existing = previous[entry.entryId]
                val record = if (existing != null && existing.index == index && existing.entry == entry) {
                    existing
                } else {
                    // Media3 stores a PreloadMediaSource wrapper, not the original source.
                    // Remove by MediaItem so replacement also releases the previous wrapper.
                    existing?.let { preloadManager.remove(it.mediaItem) }
                    val source = mediaSourceProvider(entry)
                    val mediaItem = source.mediaItem
                    preloadManager.add(source, index)
                    Record(index, entry, mediaItem, source)
                }
                next[entry.entryId] = record
            }
            previous.forEach { (entryId, record) ->
                if (entryId !in next) preloadManager.remove(record.mediaItem)
            }
            records = next
            val anchorEntryId = mutableState.value.anchorEntryId?.takeIf(next::containsKey)
            setAnchor(anchorEntryId)
            mutableState.value = mutableState.value.copy(
                entryIds = entries.map(AMediaQueueEntry::entryId),
                anchorEntryId = anchorEntryId,
            )
            preloadManager.invalidate()
        }
    }

    override fun hint(entryId: String?): AMediaCommandResult {
        if (entryId != null && entryId !in acceptedEntryIds) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.EntryNotFound)
        }
        return runOnPlayerThread {
            setAnchor(entryId)
            mutableState.value = mutableState.value.copy(anchorEntryId = entryId)
        }
    }

    override fun play(
        entryId: String,
        startPositionMillis: Long,
        playWhenReady: Boolean,
    ): AMediaCommandResult {
        if (startPositionMillis < 0L) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.InvalidArgument)
        }
        if (entryId !in acceptedEntryIds) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.EntryNotFound)
        }
        return runOnPlayerThread {
            val record = records[entryId] ?: return@runOnPlayerThread
            setAnchor(entryId)
            mutableState.value = mutableState.value.copy(anchorEntryId = entryId)
            val source = preloadManager.getMediaSource(record.mediaItem) ?: record.source
            AMediaMedia3Interop.setPreloadedMedia(
                controller = playback,
                entry = record.entry,
                mediaSource = source,
                startPositionMillis = startPositionMillis,
                playWhenReady = playWhenReady,
            )
        }
    }

    override fun setPreloadingEnabled(enabled: Boolean): AMediaCommandResult = runOnPlayerThread {
        targetControl.enabled = enabled
        mutableState.value = mutableState.value.copy(preloadingEnabled = enabled)
        preloadManager.invalidate()
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        val action = {
            playback.release()
            preloadManager.release()
            player.release()
            cacheLease?.close()
            acceptedEntryIds = emptySet()
            records.clear()
            mutableState.value = AMediaPreloadState(preloadingEnabled = false)
        }
        if (Looper.myLooper() == player.applicationLooper) action()
        else Handler(player.applicationLooper).post(action)
    }

    private fun setAnchor(entryId: String?) {
        val index = entryId?.let(records::get)?.index ?: C.INDEX_UNSET
        targetControl.anchorIndex = index
        preloadManager.setCurrentPlayingIndex(index)
        preloadManager.invalidate()
    }

    private fun runOnPlayerThread(action: () -> Unit): AMediaCommandResult {
        if (released.get()) return AMediaCommandResult.Rejected(AMediaCommandRejection.Released)
        if (Looper.myLooper() == player.applicationLooper) {
            action()
        } else {
            Handler(player.applicationLooper).post {
                if (!released.get()) action()
            }
        }
        return AMediaCommandResult.Accepted
    }
}
