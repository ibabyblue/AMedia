# AMedia

AMedia is a Kotlin-first audio and video playback toolkit for Android and
Jetpack Compose. It builds on AndroidX Media3 while keeping Media3's unstable UI
and preload APIs behind AMedia-owned public contracts.

The project is under active development toward `0.1.0`.

## Requirements

- Android API 23+
- Java 17
- Kotlin 2.3.10
- Jetpack Compose for UI modules

## Modules

- `amedia-core`: media models, controller contracts, and foreground playback.
- `amedia-compose`: unstyled Compose video and control primitives.
- `amedia-feed`: one-player short-video feed coordination and preloading.
- `amedia-session`: background playback and MediaSession integration.
- `amedia-cache`: opt-in streaming cache support.
- `amedia-compose-material3`: optional ready-to-use controls.
- `amedia`: convenience dependency for core and Compose playback.
- `example`: executable integration and diagnostics sample.

## License

MIT
