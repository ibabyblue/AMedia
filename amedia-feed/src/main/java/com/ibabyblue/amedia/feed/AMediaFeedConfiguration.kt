package com.ibabyblue.amedia.feed

public data class AMediaFeedPreloadPolicy(
    public val nextLoadedDurationMillis: Long = 3_000L,
    public val previousLoadedDurationMillis: Long = 1_000L,
    public val trackSelectionDistance: Int = 2,
    public val enabled: Boolean = true,
) {
    init {
        require(nextLoadedDurationMillis >= 0L)
        require(previousLoadedDurationMillis >= 0L)
        require(trackSelectionDistance >= 1)
    }
}

public data class AMediaFeedConfiguration(
    public val preloadPolicy: AMediaFeedPreloadPolicy = AMediaFeedPreloadPolicy(),
    public val networkPreloadPolicy: AMediaFeedNetworkPreloadPolicy =
        AMediaFeedNetworkPreloadPolicy.UnmeteredOnly,
    public val retainedPositionCount: Int = 50,
    public val minimumRetainedPositionMillis: Long = 1_000L,
) {
    init {
        require(retainedPositionCount >= 0)
        require(minimumRetainedPositionMillis >= 0L)
    }
}

public enum class AMediaFeedNetworkPreloadPolicy {
    Always,
    UnmeteredOnly,
    Never,
}
