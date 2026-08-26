package com.ibabyblue.amedia.core

public data class AMediaControllerConfiguration(
    public val progressIntervalMillis: Long = 250L,
    public val initialVolume: Float = 1f,
    public val initiallyMuted: Boolean = false,
    public val initialSpeed: Float = 1f,
    public val repeatMode: AMediaRepeatMode = AMediaRepeatMode.Off,
    public val subtitlesEnabled: Boolean = true,
    public val audioPolicy: AMediaAudioPolicy = AMediaAudioPolicy.Media,
    public val handleAudioBecomingNoisy: Boolean = true,
    public val logger: AMediaLogger = AMediaLogger.None,
) {
    init {
        require(progressIntervalMillis >= 50L) { "progressIntervalMillis must be at least 50 ms" }
        require(initialVolume in 0f..1f) { "initialVolume must be between 0 and 1" }
        require(initialSpeed > 0f) { "initialSpeed must be positive" }
    }
}

public enum class AMediaRepeatMode {
    Off,
    One,
    All,
}

public enum class AMediaAudioPolicy {
    Media,
    MutedInlineVideo,
    Ambient,
}

public enum class AMediaLogLevel {
    Debug,
    Warning,
    Error,
}

public fun interface AMediaLogger {
    public fun log(level: AMediaLogLevel, message: String, throwable: Throwable?)

    public companion object {
        public val None: AMediaLogger = AMediaLogger { _, _, _ -> }
    }
}
