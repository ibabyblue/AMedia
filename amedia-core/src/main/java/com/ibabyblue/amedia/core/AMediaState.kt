package com.ibabyblue.amedia.core

public enum class AMediaPlaybackStatus {
    Idle,
    Preparing,
    Ready,
    Playing,
    Paused,
    Buffering,
    Ended,
    Failed,
}

public enum class AMediaSuppressionReason {
    Lifecycle,
    ScreenHidden,
    BusinessOverlay,
    AudioFocus,
    Network,
}

public enum class AMediaErrorCategory {
    Network,
    Source,
    Decoder,
    Drm,
    Unsupported,
    Unexpected,
}

public data class AMediaError(
    public val category: AMediaErrorCategory,
    public val message: String,
    public val isRecoverable: Boolean,
    public val cause: Throwable? = null,
)

public data class AMediaVideoSize(
    public val width: Int,
    public val height: Int,
    public val pixelWidthHeightRatio: Float = 1f,
)

public enum class AMediaTrackType {
    Audio,
    Video,
    Text,
    Unknown,
}

public data class AMediaTrack(
    public val id: String,
    public val type: AMediaTrackType,
    public val label: String? = null,
    public val language: String? = null,
    public val mimeType: String? = null,
    public val selected: Boolean = false,
    public val supported: Boolean = true,
)

public data class AMediaCapabilities(
    public val canPlay: Boolean = true,
    public val canPause: Boolean = true,
    public val canSeek: Boolean = false,
    public val canSkipPrevious: Boolean = false,
    public val canSkipNext: Boolean = false,
    public val canSetSpeed: Boolean = true,
    public val canSetTracks: Boolean = false,
    public val hasVideoOutput: Boolean = false,
    public val supportsBackgroundPlayback: Boolean = false,
    public val supportsPictureInPicture: Boolean = false,
)

public data class AMediaState(
    public val controllerId: String,
    public val generation: Long = 0L,
    public val status: AMediaPlaybackStatus = AMediaPlaybackStatus.Idle,
    public val queue: List<AMediaQueueEntry> = emptyList(),
    public val currentIndex: Int = -1,
    public val playWhenReady: Boolean = false,
    public val isPlaying: Boolean = false,
    public val suppressionReasons: Set<AMediaSuppressionReason> = emptySet(),
    public val isFirstFrameRendered: Boolean = false,
    public val isMuted: Boolean = false,
    public val volume: Float = 1f,
    public val speed: Float = 1f,
    public val repeatMode: AMediaRepeatMode = AMediaRepeatMode.Off,
    public val subtitlesEnabled: Boolean = true,
    public val selectedSubtitleTrackId: String? = null,
    public val tracks: List<AMediaTrack> = emptyList(),
    public val videoSize: AMediaVideoSize? = null,
    public val error: AMediaError? = null,
    public val capabilities: AMediaCapabilities = AMediaCapabilities(),
) {
    public val currentEntry: AMediaQueueEntry?
        get() = queue.getOrNull(currentIndex)
}

public data class AMediaProgress(
    public val entryId: String? = null,
    public val positionMillis: Long = 0L,
    public val durationMillis: Long? = null,
    public val bufferedPositionMillis: Long = 0L,
    public val isSeeking: Boolean = false,
) {
    public val fraction: Float?
        get() = durationMillis
            ?.takeIf { it > 0L }
            ?.let { (positionMillis.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f) }
}
