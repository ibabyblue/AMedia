# AMedia

AMedia is a Kotlin-first audio and video playback toolkit for Android and Jetpack Compose. It uses
AndroidX Media3 internally, but exposes stable AMedia-owned models, state, events, and Compose APIs.

The current release is `0.2.0`. The project can publish all library artifacts to Maven Local.

## Why AMedia

- One controller contract for audio, video, queues, tracks, subtitles, DRM, clipping, and seeking.
- Compose-native video output through Media3 `ContentFrame`; no `PlayerView` wrapped in
  `AndroidView`.
- One-player ordered-media preloading for audio, video, or mixed media without screen or business
  assumptions.
- Opt-in real PCM audio amplitude levels for driving caller-owned playback animation.
- Optional per-item bypass, read-only, read-through, and cache-only disk policies.
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
| `amedia-preload` | Business-agnostic ordered-media playback and bounded preloading |
| `amedia-cache` | Optional streaming cache, LRU eviction, inspection, and explicit maintenance |
| `amedia-session` | Background playback, MediaSession, notification hooks and resumption |
| `amedia` | Convenience dependency for core plus unstyled Compose playback |
| `example` | Runnable foreground, preload, cache, subtitle, PiP and Session examples |

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
    implementation("io.github.ibabyblue:amedia:0.2.0")
    implementation("io.github.ibabyblue:amedia-compose-material3:0.2.0")
    implementation("io.github.ibabyblue:amedia-preload:0.2.0")
}
```

Use only the feature artifacts you need. `amedia` intentionally does not pull in cache, preload,
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

## Optional audio amplitude levels

Audio amplitude is an opt-in capability in `amedia-core`; it is not a separate artifact. The
default factories do not install an analyzer, do not force decoded PCM output, and do not perform
per-buffer analysis. Enable it only for a controller whose UI needs real playback amplitude:

```kotlin
val controller = AMediaControllerFactory.create(
    context = context,
    audioAmplitudeConfiguration = AMediaAudioAmplitudeConfiguration(
        updatesPerSecond = 20,
    ),
)
val amplitude = requireNotNull(controller.audioAmplitudeSourceOrNull())

lifecycleScope.launch {
    amplitude.audioAmplitudeState.collect { state ->
        when (state) {
            is AMediaAudioAmplitudeState.Active -> {
                animateWith(peak = state.level.peak, rms = state.level.rms)
            }
            is AMediaAudioAmplitudeState.Inactive -> stopAnimation()
            is AMediaAudioAmplitudeState.Unavailable -> showStaticPlaybackUi()
        }
    }
}
```

`peak` and `rms` are normalized `0..1` mono levels calculated from all decoded channels after
Media3 speed and silence-skipping processing. Controller volume is applied, so mute reports
`Inactive(Muted)`; Android's system output volume is intentionally not included. The default is
20 updates per second and the supported range is 10–30. `StateFlow` exposes only the latest window,
so slow collectors skip old levels and multiple collectors share one calculation. PCM aggregation
also pauses when the flow has no collectors.

The same optional configuration is available on `rememberAMediaController`,
`AMediaCachedControllerFactory`, and `AMediaPreloadSessionFactory`. Cached and preloaded audio or
video is measured only while it is actually playing; preload work does not generate levels. A local
`AMediaSessionService` can opt in by overriding `audioAmplitudeConfiguration()`, but real-time
levels are deliberately not transported through `AMediaSessionConnection` across Binder.

Amplitude analysis never requests `RECORD_AUDIO`, exposes raw PCM, persists samples, or changes
playback success. If a device/output pipeline cannot provide analyzable PCM, playback continues and
the source reports `Unavailable`; callers should degrade to a static or timer-based UI.

## Audio and video preloading

`AMediaPreloadSession` owns one player and its matching Media3 preload manager. Its ordered entries
may contain audio, video, or both. The application decides how pages, carousels, playlists, and
business models map to entry IDs.

```kotlin
val cache = AMediaCacheFactory.create(context)
val session = AMediaPreloadSessionFactory.create(context, cache)

session.update(entries)
session.hint(entries.getOrNull(expectedIndex)?.entryId)
session.play(entries[currentIndex].entryId)

// The owner releases in its lifecycle scope.
session.release()
cache.close()
```

The default technical budget loads 3 seconds forward, 1 second backward, selects tracks within
distance 2, and caps total preload buffers at 32 MiB. `hint` only reprioritizes work; `play` switches
the shared player to the preloaded source. The application owns pagination, network/data-saver
rules, active-page selection, and playback-position persistence.

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
reference counting. Per-item policies are:

- `None`: bypass the cache.
- `ReadOnly`: read hits, fetch misses, and do not write new bytes.
- `ReadThrough`: read hits and write upstream misses.
- `CacheOnly`: fail when required bytes are absent.

Automatic LRU eviction keeps the cache below `AMediaCacheConfiguration.maxBytes`. Applications can
also inspect `keys`, query `cachedBytes`, remove a resource, clear everything, clear spans older than
a cutoff, or trim immediately to a smaller size:

```kotlin
cache.remove(item)
cache.remove(listOf("message-audio:1", "message-audio:2"))
cache.clear(AMediaCacheClearPolicy.OlderThan(cutoffEpochMillis))
cache.clear(AMediaCacheClearPolicy.TrimToBytes(128L * 1024L * 1024L))
```

For isolated audio, video, account, or product caches under one global LRU budget, open logical
spaces from one manager. Equal public cache keys never collide across spaces, while the backing
store still has one automatic capacity limit:

```kotlin
val manager = AMediaCacheManagerFactory.create(
    context,
    AMediaCacheConfiguration(maxBytes = 512L * 1024L * 1024L),
)
val audioCache = manager.open(AMediaCacheSpace.Named("audio"))
val videoCache = manager.open(AMediaCacheSpace.Named("video"))

val snapshot = manager.snapshot()
manager.clear(
    scope = AMediaCacheScope.Spaces(setOf(AMediaCacheSpace.Named("audio"))),
    policy = AMediaCacheClearPolicy.OlderThan(cutoffEpochMillis),
)

audioCache.close()
videoCache.close()
manager.close()
```

Create managers with different `directoryName` values only when physical stores are required. Each
physical store has its own global capacity. `AMediaCacheFactory` remains the convenient,
backwards-compatible default-space factory.

For conversation audio, model each conversation as a logical space and each message as a stable
resource key. Space names accept only 1-64 ASCII letters, digits, `.`, `_`, or `-`, so hash or encode
untrusted IDs instead of using them directly:

```kotlin
val dialogueSpace = AMediaCacheSpace.Named("dialogue-${stableHash(dialogueId)}")
val dialogueCache = manager.open(dialogueSpace)
val audioItem = AMediaItem(
    mediaId = messageId,
    uri = Uri.parse(mp3Url),
    metadata = AMediaMetadata(contentType = AMediaContentType.Speech),
    http = AMediaHttpConfiguration(cacheKey = "message-audio:$messageId"),
    cachePolicy = AMediaCachePolicy.ReadThrough,
)

// Playback and preloading use dialogueCache. To delete the whole conversation later:
manager.clear(AMediaCacheScope.Spaces(setOf(dialogueSpace)))
```

A logical space is encoded into Media3 cache keys; it is not a `dialogueId` subdirectory and cached
media is stored as opaque spans rather than original `.mp3` files. Do not delete files or
subdirectories inside `cache.directory` while AMedia owns the store. Pause or release writers, then
use `dialogueCache.clear()`, manager-scoped `clear`, or `remove(cacheKey)`. If complete physical
separation is required, use a distinct `directoryName`; deleting that store is only safe after all
of its managers, caches, controllers, and preload sessions have been closed.

Maintenance is synchronous; run large operations away from the main thread. Pause or release
controllers and preload sessions that may be writing before removing spans.

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
catalog service are intentionally outside the current scope.

See [architecture](docs/architecture.md) for invariants and [QA](docs/qa.md) for release checks.

## Verification

```bash
./gradlew test lint apiCheck assembleRelease :example:assembleDebug
```

## License

MIT
