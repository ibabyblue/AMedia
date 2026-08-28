# AMedia 0.2.0 QA checklist

## Automated gates

Run from the repository root:

```bash
./gradlew test lint apiCheck assembleRelease :example:assembleDebug
```

Expected: all tasks succeed without an Android Lint baseline. `apiCheck` must compare every library
module with its checked-in ABI file.

## Foreground video

- Open Player in the example app; expect the poster before playback and one active surface.
- Play, pause, seek, double-tap both sides, mute, change speed, and toggle subtitles; expect state and
  controls to remain synchronized.
- Background and foreground the app; default foreground video pauses without losing its position.
- Enter PiP on API 26+; expect the activity to enter PiP and playback to remain controlled by policy.
- Rotate or recreate the activity; expect no leaked player and no duplicate audio.

## Generic preloading

- Update an ordered video set, hint the next entry, then play it; expect one shared player and the
  preloaded MediaSource to be reused.
- Repeat with audio-only and mixed audio/video entries; expect the same preload behavior without a
  video-surface requirement.
- Append entries without reordering existing IDs; expect unchanged MediaSources to be retained.
- Disable preloading while active playback continues, then enable it and expect priorities to resume.
- Call `hint(null)`; expect pending priority to clear without pausing active playback.
- Keep page selection, pagination, network policy, and playback-position persistence in the example
  or application layer; no public preload type may reference Compose Pager or Feed models.

## Audio and Session

- Connect to the Session page and play both queue entries from app and notification controls.
- Lock the screen with audio using `Default`; expect playback and system controls to continue.
- Use a video item with `Default` or `Pause`; background the app and expect pause, then foreground and
  expect policy-driven resume only if it had been playing.
- Use `ContinueAudio`; expect audio to continue with video output detached.
- Kill and recreate the service with a test resumption provider; expect queue index and position to
  restore.

## Audio amplitude

- Create every controller type without amplitude configuration; expect
  `audioAmplitudeSourceOrNull()` to return `null` and the normal Media3 output path to remain in use.
- Opt in at 20 Hz and play local audio; expect normalized peak/RMS `Active` levels with the current
  entry ID and generation.
- Repeat with a video's audio track, cached audio, and preloaded audio; expect levels only after
  actual playback begins, never from `update` or `hint` alone.
- Change speed and enable silence skipping; expect levels from the post-processing PCM stream.
- Pause, buffer, mute, end, replace media, fail playback, and release; expect the matching
  `Inactive` reason and no stale generation to reactivate.
- Subscribe with multiple fast and slow collectors; expect one shared computation and conflated
  latest-state behavior. Remove all collectors; expect PCM aggregation work to stop.
- Exercise a device/output path that cannot expose decoded PCM; expect `Unavailable` while playback
  continues normally.
- Verify the feature requires no recording permission, exposes no raw PCM, and does not reflect
  Android system volume.
- Verify a local Session service may opt in, while its remote `AMediaSessionConnection` does not
  expose real-time amplitude.
- Run `:example:connectedDebugAndroidTest` on API 23+ before release; it covers default-off, audio,
  video/speed, cache/write-through, and preload/playback paths.

## Cache and network

- Play a remote `ReadThrough` item with a stable cache key; expect cache size to increase.
- Play `ReadOnly`; expect existing bytes to be used and cache size not to increase for misses.
- Play `CacheOnly`; expect cached ranges to play and uncached ranges to fail without network access.
- Replay after disconnecting network; expect cached ranges to play and uncached ranges to fail with a
  recoverable network error.
- Verify two cache handles for the same directory do not throw a SimpleCache lock error.
- Release or pause writers, then remove one item and clear the cache; expect size to return to zero.
- Populate multiple resources, apply `OlderThan` and `TrimToBytes`, and expect only matching or
  least-recently-used spans to be removed.
- Equal public keys in two named spaces remain isolated, while their aggregate size obeys one global
  LRU limit.
- Batch resource removal leaves unselected keys intact; manager clear affects only selected spaces.
- Manager snapshots report aggregate bytes and per-space resource counts consistently.
- Clear one conversation-style named space and verify other conversations remain intact; do not
  assert or depend on physical filenames or per-space subdirectories.
- `:amedia-cache:assembleDebugAndroidTest` compiles the real `SimpleCache` management suite; run it
  on an API 23+ device before release.
- Verify authorization headers on progressive, HLS manifest/segments, DASH manifest/segments, and
  subtitle requests with a local test server.

## Error and lifecycle boundaries

- Force a network failure; expect two `RetryScheduled` events with bounded exponential delays, then
  one terminal `Failed` event if recovery does not occur.
- Replace media during retry; expect the old generation never to restart.
- Repeatedly call release/close; expect idempotent behavior and no crash.
- Test audio focus loss, headset unplug, phone call interruption, and return; expect Media3 audio
  focus behavior without two simultaneous players.

## Manual-only boundaries

Notification appearance, OEM background restrictions, PiP transitions, decoder/device compatibility,
DRM license servers, real CDN headers, and network handoff need real-device or integration-service
verification; unit tests and host builds cannot prove them.
