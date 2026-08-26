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
    public val supportsBackgroundPlayback: Boolean = false,
    public val supportsPictureInPicture: Boolean = false,
    public val retryPolicy: AMediaRetryPolicy = AMediaRetryPolicy(),
    public val logger: AMediaLogger = AMediaLogger.None,
) {
    init {
        require(progressIntervalMillis >= 50L) { "progressIntervalMillis must be at least 50 ms" }
        require(initialVolume in 0f..1f) { "initialVolume must be between 0 and 1" }
        require(initialSpeed > 0f) { "initialSpeed must be positive" }
    }
}

public data class AMediaRetryPolicy(
    public val maxAutomaticRetries: Int = 2,
    public val initialDelayMillis: Long = 500L,
    public val maxDelayMillis: Long = 2_000L,
) {
    init {
        require(maxAutomaticRetries >= 0) { "maxAutomaticRetries must be non-negative" }
        require(initialDelayMillis >= 0L) { "initialDelayMillis must be non-negative" }
        require(maxDelayMillis >= initialDelayMillis) {
            "maxDelayMillis must not be smaller than initialDelayMillis"
        }
    }

    internal fun delayForAttempt(attempt: Int): Long {
        require(attempt >= 1)
        var delay = initialDelayMillis
        repeat(attempt - 1) {
            delay = (delay * 2L).coerceAtMost(maxDelayMillis)
        }
        return delay
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
