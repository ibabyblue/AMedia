package com.ibabyblue.amedia.example

import android.app.Activity
import android.app.PictureInPictureParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ibabyblue.amedia.cache.AMediaCacheConfiguration
import com.ibabyblue.amedia.cache.AMediaCacheFactory
import com.ibabyblue.amedia.cache.AMediaCachedControllerFactory
import com.ibabyblue.amedia.compose.AMediaContentScale
import com.ibabyblue.amedia.compose.AMediaLifecycleBinding
import com.ibabyblue.amedia.compose.AMediaVideo
import com.ibabyblue.amedia.compose.material3.AMediaMaterialPlayer
import com.ibabyblue.amedia.core.AMediaCachePolicy
import com.ibabyblue.amedia.core.AMediaAudioAmplitudeConfiguration
import com.ibabyblue.amedia.core.AMediaAudioAmplitudeState
import com.ibabyblue.amedia.core.AMediaContentType
import com.ibabyblue.amedia.core.AMediaController
import com.ibabyblue.amedia.core.AMediaControllerConfiguration
import com.ibabyblue.amedia.core.AMediaItem
import com.ibabyblue.amedia.core.AMediaMetadata
import com.ibabyblue.amedia.core.AMediaQueueEntry
import com.ibabyblue.amedia.core.AMediaRepeatMode
import com.ibabyblue.amedia.core.AMediaSubtitle
import com.ibabyblue.amedia.core.audioAmplitudeSourceOrNull
import com.ibabyblue.amedia.preload.AMediaPreloadSessionFactory
import com.ibabyblue.amedia.session.AMediaSessionConnection
import com.ibabyblue.amedia.session.AMediaSessionConnectionState
import kotlinx.coroutines.flow.distinctUntilChanged

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AMediaExampleApp()
            }
        }
    }
}

private enum class DemoPage { Player, Amplitude, Preload, Session, Cache }

@Composable
private fun AMediaExampleApp() {
    var selected by remember { mutableIntStateOf(0) }
    val pages = DemoPage.entries
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pages.forEachIndexed { index, page ->
                    Button(onClick = { selected = index }, modifier = Modifier.weight(1f)) {
                        Text(page.name)
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                when (pages[selected]) {
                    DemoPage.Player -> ForegroundPlayerDemo()
                    DemoPage.Amplitude -> AudioAmplitudeDemo()
                    DemoPage.Preload -> PreloadDemo()
                    DemoPage.Session -> SessionDemo()
                    DemoPage.Cache -> CachedPlayerDemo()
                }
            }
        }
    }
}

@Composable
private fun AudioAmplitudeDemo() {
    val context = LocalContext.current
    val controller = com.ibabyblue.amedia.compose.rememberAMediaController(
        audioAmplitudeConfiguration = AMediaAudioAmplitudeConfiguration(),
    )
    AMediaLifecycleBinding(controller)
    LaunchedEffect(controller) {
        controller.setMedia(
            sampleAudio(context.packageName, "PCM-derived amplitude"),
            playWhenReady = false,
        )
    }
    AudioController(
        controller = controller,
        title = "Local audio amplitude",
        showsAmplitude = true,
    )
}

@Composable
private fun ForegroundPlayerDemo() {
    val context = LocalContext.current
    val controller = com.ibabyblue.amedia.compose.rememberAMediaController(
        configuration = com.ibabyblue.amedia.core.AMediaControllerConfiguration(
            supportsPictureInPicture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
        ),
    )
    AMediaLifecycleBinding(controller)
    LaunchedEffect(controller) {
        controller.setMedia(sampleVideo(context.packageName, R.raw.sample_video), playWhenReady = false)
    }
    AMediaMaterialPlayer(
        controller = controller,
        modifier = Modifier.fillMaxSize(),
        contentScale = AMediaContentScale.Fit,
        onPictureInPicture = { enterPictureInPicture(context as Activity) },
        shutter = { DemoPoster("Foreground video", Color(0xFF253247)) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PreloadDemo() {
    val context = LocalContext.current
    val preloadSession = remember {
        AMediaPreloadSessionFactory.create(
            context,
            controllerConfiguration = AMediaControllerConfiguration(
                repeatMode = AMediaRepeatMode.One,
            ),
            audioAmplitudeConfiguration = AMediaAudioAmplitudeConfiguration(),
        )
    }
    DisposableEffect(preloadSession) { onDispose(preloadSession::release) }
    AMediaLifecycleBinding(preloadSession.playback)
    val playbackState by preloadSession.playback.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { 3 })
    val videos = remember(context.packageName) {
        listOf(
            AMediaQueueEntry("feed-video-1", sampleVideo(context.packageName, R.raw.sample_video)),
            AMediaQueueEntry("feed-video-2", sampleVideo(context.packageName, R.raw.sample_video_two)),
        )
    }
    val entryIdForPage: (Int) -> String? = { page ->
        when (page) {
            0 -> videos[0].entryId
            2 -> videos[1].entryId
            else -> null
        }
    }
    LaunchedEffect(preloadSession, videos) { preloadSession.update(videos) }
    LaunchedEffect(preloadSession, pagerState) {
        snapshotFlow { pagerState.targetPage }
            .distinctUntilChanged()
            .collect { page -> preloadSession.hint(entryIdForPage(page)) }
    }
    LaunchedEffect(preloadSession, pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val entryId = entryIdForPage(page)
                if (entryId == null) preloadSession.playback.pause()
                else preloadSession.play(entryId)
            }
    }
    VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            0, 2 -> {
                val entryIndex = if (page == 0) 0 else 1
                Box(Modifier.fillMaxSize().background(if (page == 0) Color(0xFF341E44) else Color(0xFF153A34))) {
                    if (playbackState.currentEntry?.entryId == videos[entryIndex].entryId) {
                        AMediaVideo(
                            controller = preloadSession.playback,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = AMediaContentScale.Crop,
                            shutter = { DemoPoster("Feed video ${entryIndex + 1}", Color.Transparent) },
                        )
                    } else {
                        DemoPoster("Feed video ${entryIndex + 1}", Color.Transparent)
                    }
                    AudioAmplitudeIndicator(
                        controller = preloadSession.playback,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                    )
                }
            }
            else -> DemoPoster("Image item owned by the host", Color(0xFF43321E))
        }
    }
}

@Composable
private fun SessionDemo() {
    val context = LocalContext.current
    val connection = remember {
        AMediaSessionConnection.connect(context, ExamplePlaybackService::class.java)
    }
    DisposableEffect(connection) { onDispose(connection::close) }
    val connectionState by connection.state.collectAsStateWithLifecycle()
    when (val state = connectionState) {
        AMediaSessionConnectionState.Connecting -> DemoPoster("Connecting to MediaSession…", Color(0xFF202020))
        AMediaSessionConnectionState.Closed -> DemoPoster("Session closed", Color(0xFF202020))
        is AMediaSessionConnectionState.Failed -> DemoPoster("Session failed: ${state.cause.message}", Color(0xFF4A1717))
        is AMediaSessionConnectionState.Connected -> {
            LaunchedEffect(state.controller) {
                state.controller.setQueue(
                    listOf(
                        AMediaQueueEntry("audio-1", sampleAudio(context.packageName, "Tone 330 Hz")),
                        AMediaQueueEntry("audio-2", sampleAudio(context.packageName, "Tone replay")),
                    ),
                    playWhenReady = false,
                )
            }
            AudioController(controller = state.controller, title = "Background audio queue")
        }
    }
}

@Composable
private fun CachedPlayerDemo() {
    val context = LocalContext.current
    val cache = remember {
        AMediaCacheFactory.create(context, AMediaCacheConfiguration(directoryName = "amedia-example"))
    }
    val controller = remember(cache) {
        AMediaCachedControllerFactory.create(
            context,
            cache,
            audioAmplitudeConfiguration = AMediaAudioAmplitudeConfiguration(),
        )
    }
    DisposableEffect(controller, cache) {
        onDispose {
            controller.release()
            cache.close()
        }
    }
    AMediaLifecycleBinding(controller)
    LaunchedEffect(controller) {
        controller.setMedia(
            sampleVideo(context.packageName, R.raw.sample_video).copy(
                cachePolicy = AMediaCachePolicy.ReadThrough,
            ),
            playWhenReady = false,
        )
    }
    Column(Modifier.fillMaxSize()) {
        AMediaMaterialPlayer(
            controller = controller,
            modifier = Modifier.fillMaxWidth().weight(1f),
            shutter = { DemoPoster("Read-through cache", Color(0xFF172F4A)) },
        )
        AudioAmplitudeIndicator(
            controller = controller,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        Text("Cache size: ${cache.sizeBytes / 1024L} KiB", Modifier.padding(16.dp))
    }
}

@Composable
private fun AudioController(
    controller: AMediaController,
    title: String,
    showsAmplitude: Boolean = false,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val progress by controller.progress.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(state.currentEntry?.item?.metadata?.title.orEmpty())
        Text("${progress.positionMillis / 1_000}s / ${(progress.durationMillis ?: 0L) / 1_000}s")
        if (showsAmplitude) {
            AudioAmplitudeIndicator(controller, Modifier.padding(vertical = 24.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controller.skipToPrevious() }) { Text("Previous") }
            Button(onClick = { if (state.isPlaying) controller.pause() else controller.play() }) {
                Text(if (state.isPlaying) "Pause" else "Play")
            }
            Button(onClick = { controller.skipToNext() }) { Text("Next") }
        }
    }
}

@Composable
private fun AudioAmplitudeIndicator(
    controller: AMediaController,
    modifier: Modifier = Modifier,
) {
    val source = remember(controller) { controller.audioAmplitudeSourceOrNull() }
    if (source == null) {
        Text("Audio amplitude disabled", modifier = modifier)
        return
    }
    val state by source.audioAmplitudeState.collectAsStateWithLifecycle()
    val level = (state as? AMediaAudioAmplitudeState.Active)?.level
    val animatedRms by animateFloatAsState(
        targetValue = level?.rms ?: 0f,
        label = "audio-amplitude-rms",
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size((48f + animatedRms * 72f).dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(
                        alpha = 0.35f + animatedRms * 0.65f,
                    ),
                    shape = CircleShape,
                ),
        )
        Text(
            when (val current = state) {
                is AMediaAudioAmplitudeState.Active ->
                    "Peak %.2f  RMS %.2f".format(current.level.peak, current.level.rms)
                is AMediaAudioAmplitudeState.Inactive -> "Inactive: ${current.reason}"
                is AMediaAudioAmplitudeState.Unavailable -> "Unavailable: ${current.reason}"
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun DemoPoster(label: String, color: Color) {
    Box(
        modifier = Modifier.fillMaxSize().background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

private fun sampleVideo(packageName: String, rawResource: Int): AMediaItem = AMediaItem(
    mediaId = "video-$rawResource",
    uri = androidResourceUri(packageName, rawResource),
    metadata = AMediaMetadata(title = "AMedia sample video", contentType = AMediaContentType.Video),
    subtitles = listOf(
        AMediaSubtitle(
            id = "sample-en",
            uri = androidResourceUri(packageName, R.raw.sample_subtitles),
            mimeType = "text/vtt",
            language = "en",
            label = "English",
            isDefault = true,
        ),
    ),
)

private fun sampleAudio(packageName: String, title: String): AMediaItem = AMediaItem(
    mediaId = title,
    uri = androidResourceUri(packageName, R.raw.sample_audio),
    metadata = AMediaMetadata(title = title, artist = "AMedia", contentType = AMediaContentType.Music),
)

private fun androidResourceUri(packageName: String, resource: Int): Uri =
    Uri.parse("android.resource://$packageName/$resource")

private fun enterPictureInPicture(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        activity.enterPictureInPictureMode(
            PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build(),
        )
    }
}
