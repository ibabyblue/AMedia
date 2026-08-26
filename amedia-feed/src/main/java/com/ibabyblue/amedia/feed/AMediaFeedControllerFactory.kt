package com.ibabyblue.amedia.feed

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import com.ibabyblue.amedia.core.AMediaAudioPolicy
import com.ibabyblue.amedia.core.AMediaCommandRejection
import com.ibabyblue.amedia.core.AMediaCommandResult
import com.ibabyblue.amedia.core.AMediaController
import com.ibabyblue.amedia.core.AMediaControllerConfiguration
import com.ibabyblue.amedia.core.AMediaEvent
import com.ibabyblue.amedia.core.AMediaQueueEntry
import com.ibabyblue.amedia.core.AMediaRepeatMode
import com.ibabyblue.amedia.core.AMediaSuppressionReason
import com.ibabyblue.amedia.core.ExperimentalAMediaApi
import com.ibabyblue.amedia.core.interop.AMediaMedia3Interop
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public object AMediaFeedControllerFactory {
    @JvmStatic
    @JvmOverloads
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    @OptIn(ExperimentalAMediaApi::class)
    public fun create(
        context: Context,
        configuration: AMediaFeedConfiguration = AMediaFeedConfiguration(),
        controllerConfiguration: AMediaControllerConfiguration = AMediaControllerConfiguration(
            repeatMode = AMediaRepeatMode.One,
            audioPolicy = AMediaAudioPolicy.Media,
        ),
    ): AMediaFeedController {
        val targetControl = FeedTargetPreloadStatusControl(configuration.preloadPolicy)
        val builder = DefaultPreloadManager.Builder(context.applicationContext, targetControl)
        val exoPlayerBuilder = ExoPlayer.Builder(context.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
        val player = builder.buildExoPlayer(exoPlayerBuilder)
        val preloadManager = builder.build()
        val playback = AMediaMedia3Interop.wrap(
            player = player,
            configuration = controllerConfiguration.copy(repeatMode = AMediaRepeatMode.One),
            releasePlayer = false,
        )
        return DefaultAMediaFeedController(
            context = context.applicationContext,
            player = player,
            preloadManager = preloadManager,
            targetControl = targetControl,
            playback = playback,
            configuration = configuration,
        )
    }
}

@UnstableApi
private class FeedTargetPreloadStatusControl(
    private val policy: AMediaFeedPreloadPolicy,
) : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
    var currentPlayingIndex: Int = C.INDEX_UNSET
    var enabled: Boolean = policy.enabled

    override fun getTargetPreloadStatus(index: Int): DefaultPreloadManager.PreloadStatus {
        if (!enabled || currentPlayingIndex == C.INDEX_UNSET) {
            return DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        }
        return when (val distance = index - currentPlayingIndex) {
            1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(
                policy.nextLoadedDurationMillis,
            )
            -1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(
                policy.previousLoadedDurationMillis,
            )
            else -> if (abs(distance) <= policy.trackSelectionDistance) {
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
            } else {
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@OptIn(ExperimentalAMediaApi::class)
private class DefaultAMediaFeedController(
    private val context: Context,
    private val player: ExoPlayer,
    private val preloadManager: DefaultPreloadManager,
    private val targetControl: FeedTargetPreloadStatusControl,
    override val playback: AMediaController,
    private val configuration: AMediaFeedConfiguration,
) : AMediaFeedController {
    private val released = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var preloadingRequested = configuration.preloadPolicy.enabled
    private var networkAllowsPreloading = isPreloadingAllowed(
        configuration.networkPreloadPolicy,
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .activeNetworkCapabilities()
            ?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) },
    )
    private val mutableState = MutableStateFlow(AMediaFeedState())
    override val state: StateFlow<AMediaFeedState> = mutableState.asStateFlow()
    private var mediaItems: List<MediaItem> = emptyList()
    private var mediaSources: List<MediaSource> = emptyList()
    private val retainedPositions = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > configuration.retainedPositionCount
    }
    private val networkMonitor = NetworkPreloadMonitor(context) { capabilities ->
        runOnPlayerThread {
            networkAllowsPreloading = isPreloadingAllowed(
                configuration.networkPreloadPolicy,
                capabilities?.let {
                    !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                },
            )
            applyPreloadingEligibility()
        }
    }

    init {
        applyPreloadingEligibility()
        scope.launch {
            playback.progress.collect { progress ->
                val entryId = progress.entryId ?: return@collect
                if (progress.positionMillis >= configuration.minimumRetainedPositionMillis) {
                    retainedPositions[entryId] = progress.positionMillis
                }
            }
        }
        scope.launch {
            playback.events.collect { event ->
                if (event is AMediaEvent.PlaybackEnded) retainedPositions.remove(event.entryId)
            }
        }
    }

    override fun submit(entries: List<AMediaQueueEntry>): AMediaCommandResult {
        if (entries.map { it.entryId }.toSet().size != entries.size) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.DuplicateQueueEntryId)
        }
        return runOnPlayerThread {
            mediaSources.forEach(preloadManager::remove)
            val newMediaItems = entries.map(AMediaMedia3Interop::mediaItem)
            val newMediaSources = entries.map { entry ->
                AMediaMedia3Interop.mediaSource(context = context, entry = entry)
            }
            newMediaSources.forEachIndexed { index, source -> preloadManager.add(source, index) }
            mediaItems = newMediaItems
            mediaSources = newMediaSources
            val activeEntryId = mutableState.value.activeEntryId
            val newActiveIndex = entries.indexOfFirst { it.entryId == activeEntryId }
            mutableState.value = mutableState.value.copy(
                entries = entries.toList(),
                activeIndex = newActiveIndex,
                activeEntryId = entries.getOrNull(newActiveIndex)?.entryId,
            )
            if (newActiveIndex == -1) {
                playback.pause()
                targetControl.currentPlayingIndex = C.INDEX_UNSET
            } else {
                setCurrentPlayingIndex(newActiveIndex)
            }
            preloadManager.invalidate()
        }
    }

    override fun activate(index: Int): AMediaCommandResult {
        if (index !in state.value.entries.indices) {
            return AMediaCommandResult.Rejected(AMediaCommandRejection.InvalidArgument)
        }
        return runOnPlayerThread { setCurrentPlayingIndex(index) }
    }

    override fun deactivate(): AMediaCommandResult = runOnPlayerThread {
        playback.addSuppression(AMediaSuppressionReason.ScreenHidden)
    }

    override fun setPreloadingEnabled(enabled: Boolean): AMediaCommandResult = runOnPlayerThread {
        preloadingRequested = enabled
        applyPreloadingEligibility()
    }

    override fun addSuppression(reason: AMediaSuppressionReason): AMediaCommandResult =
        playback.addSuppression(reason)

    override fun removeSuppression(reason: AMediaSuppressionReason): AMediaCommandResult =
        playback.removeSuppression(reason)

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        val releaseAction = {
            playback.release()
            networkMonitor.close()
            preloadManager.release()
            player.release()
            mutableState.value = AMediaFeedState(preloadingEnabled = false)
            scope.cancel()
        }
        if (android.os.Looper.myLooper() == player.applicationLooper) releaseAction()
        else android.os.Handler(player.applicationLooper).post(releaseAction)
    }

    private fun setCurrentPlayingIndex(index: Int) {
        val entry = mutableState.value.entries[index]
        playback.removeSuppression(AMediaSuppressionReason.ScreenHidden)
        targetControl.currentPlayingIndex = index
        preloadManager.setCurrentPlayingIndex(index)
        preloadManager.invalidate()
        val position = retainedPositions[entry.entryId] ?: entry.startPositionMillis
        val mediaSource = preloadManager.getMediaSource(mediaItems[index])
        if (mediaSource != null) {
            AMediaMedia3Interop.setPreloadedMedia(
                controller = playback,
                entry = entry,
                mediaSource = mediaSource,
                startPositionMillis = position,
                playWhenReady = true,
            )
        } else {
            playback.setQueue(
                entries = listOf(entry),
                startIndex = 0,
                startPositionMillis = position,
                playWhenReady = true,
            )
        }
        mutableState.value = mutableState.value.copy(
            activeIndex = index,
            activeEntryId = entry.entryId,
        )
    }

    private fun applyPreloadingEligibility() {
        val enabled = preloadingRequested && networkAllowsPreloading
        targetControl.enabled = enabled
        mutableState.value = mutableState.value.copy(
            preloadingEnabled = enabled,
            preloadingRequested = preloadingRequested,
            networkAllowsPreloading = networkAllowsPreloading,
        )
        preloadManager.invalidate()
    }

    private fun runOnPlayerThread(action: () -> Unit): AMediaCommandResult {
        if (released.get()) return AMediaCommandResult.Rejected(AMediaCommandRejection.Released)
        if (android.os.Looper.myLooper() == player.applicationLooper) {
            action()
        } else {
            android.os.Handler(player.applicationLooper).post {
                if (!released.get()) action()
            }
        }
        return AMediaCommandResult.Accepted
    }
}

private fun ConnectivityManager.activeNetworkCapabilities(): NetworkCapabilities? =
    activeNetwork?.let(::getNetworkCapabilities)

internal fun isPreloadingAllowed(
    policy: AMediaFeedNetworkPreloadPolicy,
    isMetered: Boolean?,
): Boolean = when (policy) {
    AMediaFeedNetworkPreloadPolicy.Always -> true
    AMediaFeedNetworkPreloadPolicy.Never -> false
    AMediaFeedNetworkPreloadPolicy.UnmeteredOnly -> isMetered != true
}

private class NetworkPreloadMonitor(
    context: Context,
    private val onChanged: (NetworkCapabilities?) -> Unit,
) : AutoCloseable {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = dispatch(network)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            onChanged(capabilities)
        }
        override fun onLost(network: Network) {
            onChanged(connectivityManager.activeNetworkCapabilities())
        }

        private fun dispatch(network: Network) {
            onChanged(connectivityManager.getNetworkCapabilities(network))
        }
    }

    init {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } else {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback,
            )
        }
    }

    override fun close() {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }
}
