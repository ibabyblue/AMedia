package com.ibabyblue.amedia.core

import kotlinx.coroutines.flow.StateFlow

/** Configures opt-in audio amplitude levels for an AMedia-owned playback pipeline. */
public data class AMediaAudioAmplitudeConfiguration(
    public val updatesPerSecond: Int = 20,
) {
    init {
        require(updatesPerSecond in 10..30) {
            "updatesPerSecond must be between 10 and 30"
        }
    }
}

/** One normalized mono amplitude window derived from decoded playback PCM. */
public data class AMediaAudioAmplitudeLevel(
    public val peak: Float,
    public val rms: Float,
) {
    init {
        require(peak.isFinite() && peak in 0f..1f) { "peak must be between 0 and 1" }
        require(rms.isFinite() && rms in 0f..1f) { "rms must be between 0 and 1" }
    }
}

public enum class AMediaAudioAmplitudeInactiveReason {
    NoMedia,
    Preparing,
    AwaitingSamples,
    Paused,
    Buffering,
    Muted,
    Ended,
    NoAudioTrack,
    Failed,
    Released,
}

public enum class AMediaAudioAmplitudeUnavailableReason {
    UnsupportedPlaybackPipeline,
    UnsupportedOutput,
}

/** Latest audio amplitude status. This is continuous state, not an [AMediaEvent]. */
public sealed interface AMediaAudioAmplitudeState {
    public data class Unavailable(
        public val reason: AMediaAudioAmplitudeUnavailableReason,
    ) : AMediaAudioAmplitudeState

    public data class Inactive(
        public val reason: AMediaAudioAmplitudeInactiveReason,
    ) : AMediaAudioAmplitudeState

    public data class Active(
        public val entryId: String,
        public val generation: Long,
        public val level: AMediaAudioAmplitudeLevel,
        public val sampledAtElapsedRealtimeNanos: Long,
    ) : AMediaAudioAmplitudeState {
        init {
            require(entryId.isNotBlank()) { "entryId must not be blank" }
            require(generation >= 0L) { "generation must be non-negative" }
            require(sampledAtElapsedRealtimeNanos >= 0L) {
                "sampledAtElapsedRealtimeNanos must be non-negative"
            }
        }
    }
}

/** Optional high-frequency capability shared by every observer of one playback controller. */
public interface AMediaAudioAmplitudeSource {
    public val audioAmplitudeState: StateFlow<AMediaAudioAmplitudeState>
}

/** Returns the installed amplitude capability, or null when it was not requested at creation. */
public fun AMediaController.audioAmplitudeSourceOrNull(): AMediaAudioAmplitudeSource? = when (this) {
    is AMediaAudioAmplitudeSource -> this
    is Media3AMediaController -> audioAmplitudeSource
    else -> null
}
