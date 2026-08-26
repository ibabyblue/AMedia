# AMedia

AMedia is a Kotlin-first audio and video playback toolkit for Android and Jetpack Compose. It uses
AndroidX Media3 internally, but exposes stable AMedia-owned models, state, events, and Compose APIs.

The project is being prepared for its first `0.1.0` release. It currently publishes local Maven
artifacts as `0.1.0-SNAPSHOT`.

## Why AMedia

- One controller contract for audio, video, queues, tracks, subtitles, DRM, clipping, and seeking.
- Compose-native video output through Media3 `ContentFrame`; no `PlayerView` wrapped in
  `AndroidView`.
- One-player short-video feed coordination with settled-page activation and Media3 preloading.
- Optional per-item read-through cache instead of forcing every item through a cache.
- Optional MediaSession service for background audio, system controls, notification customization,
  and playback resumption.
- Explicit ownership: every factory-created controller is released by its owner, while supplied
  controllers are never released by UI composables.
- Public ABI snapshots checked in CI.

## Requirements

- Android API 23+
- compileSdk 36
- Java 17
- Kotlin 2.3.10
- AndroidX Media3 1.11.0
- Jetpack Compose for UI modules

## Modules

| Module | Purpose |
| --- | --- |
| `amedia-core` | Models, `AMediaController`, Media3 playback, retry, HTTP headers, tracks and events |
| `amedia-compose` | Unstyled `AMediaVideo`, controller ownership and lifecycle binding |
| `amedia-compose-material3` | Optional accessible Material 3 controls |
| `amedia-feed` | One-player vertical-feed coordination and bounded preloading |
| `amedia-cache` | Optional per-item read-through streaming cache |
| `amedia-session` | Background playback, MediaSession, notification hooks and resumption |
| `amedia` | Convenience dependency for core plus unstyled Compose playback |
| `example` | Runnable foreground, feed, cache, subtitle, PiP and Session examples |

## Local installation

```bash
./gradlew publishToMavenLocal
```

```kotlin
repositories {
    mavenLocal()
    google()
    mavenCentral()
}

dependencies {
    implementation("io.github.ibabyblue:amedia:0.1.0-SNAPSHOT")
    implementation("io.github.ibabyblue:amedia-compose-material3:0.1.0-SNAPSHOT")
}
```

Use only the feature artifacts you need. `amedia` intentionally does not pull in cache, feed,
Material 3, or Session support.

## Foreground Compose player

```kotlin
@Composable
fun Video(url: String) {
    val controller = rememberAMediaController()
    AMediaLifecycleBinding(controller)

    LaunchedEffect(controller, url) {
        controller.setMedia(
            AMediaItem(
                mediaId = url,
                uri = Uri.parse(url),
                metadata = AMediaMetadata(contentType = AMediaContentType.Video),
                http = AMediaHttpConfiguration(
                    headers = mapOf("Authorization" to "Bearer ..."),
                ),
            ),
            playWhenReady = true,
        )
    }

    AMediaMaterialPlayer(controller, Modifier.fillMaxSize())
}
```

Commands are serialized onto the Media3 application looper and return an immediate
`AMediaCommandResult`. Observe `state`, `progress`, and `events` for authoritative asynchronous
results. Network errors retry twice by default with bounded exponential backoff.

## Short-video feed

Create one feed controller for one feed screen and submit video entries only. Image pages remain
owned by the host UI.

```kotlin
val feed = rememberAMediaFeedController()
LaunchedEffect(entries) { feed.submit(entries) }

AMediaFeedPagerBinding(
    controller = feed,
    pagerState = pagerState,
    entryIndexForPage = { page -> videoEntryIndexByPage[page] }, // null for an image page
)

if (feedState.activeIndex == entryIndex) {
    AMediaVideo(feed.playback, Modifier.fillMaxSize(), contentScale = AMediaContentScale.Crop)
}
```

Activation follows `PagerState.settledPage`, so a swipe never starts two videos. The default policy
loads 3 seconds ahead, 1 second behind, selects tracks within distance 2, retains 50 playback
positions, and preloads only on an unmetered network. `Always` and `Never` network policies are
available explicitly.

## Optional cache

```kotlin
val cache = AMediaCacheFactory.create(context)
val controller = AMediaCachedControllerFactory.create(context, cache)

controller.setMedia(
    item.copy(
        cachePolicy = AMediaCachePolicy.ReadThrough,
        http = item.http.copy(cacheKey = "stable-content-id"),
    ),
)

controller.release()
cache.close()
```

The default cache is a 256 MiB LRU. The same directory is shared safely inside the process through
reference counting. An item with `AMediaCachePolicy.None` bypasses the cache. Pause or release
controllers that may be writing before calling `remove` or `clear`.

## Background playback

Declare a concrete service:

```kotlin
class PlaybackService : AMediaSessionService()
```

```xml
<service
    android:name=".PlaybackService"
    android:exported="true"
    android:foregroundServiceType="mediaPlayback">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
```

Connect from UI with `AMediaSessionConnection.connect`. Override `mediaNotificationProvider`,
`configureSession`, or `playbackResumptionProvider` only when the defaults are insufficient.

Per-item background behavior is explicit:

- `Default`: Session audio continues; video pauses and resumes with the app process lifecycle.
- `Pause`: pause and resume with the app process lifecycle.
- `Continue`: continue audio/video playback.
- `ContinueAudio`: continue playback while detaching video output.

Android 13+ applications should request notification permission when they want the playback
notification to be visible. Media3 still owns foreground-service timing and system media controls.

## Scope

AMedia supports progressive media plus Media3 HLS and DASH, external subtitles, Widevine-style DRM
configuration, queues, and streaming cache. Offline downloads, casting, ads, and a MediaLibrary
catalog service are intentionally outside `0.1.0`.

See [architecture](docs/architecture.md) for invariants and [QA](docs/qa.md) for release checks.

## Verification

```bash
./gradlew test lint apiCheck assembleRelease :example:assembleDebug
```

## License

MIT
