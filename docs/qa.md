# AMedia 0.1.0 QA checklist

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

## Feed

- Slowly and rapidly swipe video → image → video; only the settled video may play.
- Stop midway, move away, then return; expect the saved position after the configured 1-second
  threshold.
- Finish a video, move away, and return; expect it to start from its queue start position.
- On metered connectivity, expect default preloading to stop while active playback continues.
- Change to an unmetered network; expect preloading to become eligible again.

## Audio and Session

- Connect to the Session page and play both queue entries from app and notification controls.
- Lock the screen with audio using `Default`; expect playback and system controls to continue.
- Use a video item with `Default` or `Pause`; background the app and expect pause, then foreground and
  expect policy-driven resume only if it had been playing.
- Use `ContinueAudio`; expect audio to continue with video output detached.
- Kill and recreate the service with a test resumption provider; expect queue index and position to
  restore.

## Cache and network

- Play a remote `ReadThrough` item with a stable cache key; expect cache size to increase.
- Replay after disconnecting network; expect cached ranges to play and uncached ranges to fail with a
  recoverable network error.
- Verify two cache handles for the same directory do not throw a SimpleCache lock error.
- Release or pause writers, then remove one item and clear the cache; expect size to return to zero.
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
