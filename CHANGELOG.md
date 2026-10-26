# Changelog

All notable changes to AMedia are documented in this file.

## 0.1.1 - 2026-10-26

### Fixed

- Release evicted and replaced preload sources by MediaItem identity. Media3 wraps the source
  supplied to `add`, so removing the original source instance could silently fail and retain old
  preloaded resources as the application advanced its bounded window.
- Cover repeated window updates, changed entries, reordering, empty sets, stable-source reuse,
  and idempotent release with device regression tests against the real Media3 manager.
- Verify active video survives preload eviction, repeated switches keep loader threads bounded,
  and session release restores the thread baseline using local sample videos.
- Preserve the 0.1.0 public API and dependency versions; this is a 0.1.x maintenance release.

## 0.1.0 - 2026-08-26

Initial release.

### Added

- Kotlin-first Media3 audio and video controller with queues, tracks, subtitles, DRM, clipping,
  retry, per-request headers, lifecycle policy, and observable state and events.
- Unstyled Compose video output plus optional accessible Material 3 controls.
- Business-agnostic, one-player ordered-media preloading for audio, video, and mixed lists.
- Optional streaming disk cache with per-item policies, one global size-bounded LRU, logical cache
  spaces, snapshots, single and batch removal, scoped clearing, age clearing, and explicit trimming.
- MediaSession service support for background audio, system controls, notifications, and playback
  resumption.
- Example application, API compatibility snapshots, unit tests, lint checks, and device cache tests.
