package com.ibabyblue.amedia.preload

/** Technical preload budgets for an ordered set of audio, video, or mixed media. */
public data class AMediaPreloadConfiguration(
    public val forwardLoadedDurationMillis: Long = 3_000L,
    public val backwardLoadedDurationMillis: Long = 1_000L,
    public val trackSelectionDistance: Int = 2,
    public val maxPreloadBufferBytes: Int = 32 * 1024 * 1024,
    public val enabled: Boolean = true,
    public val userAgent: String = "AMedia/0.1",
) {
    init {
        require(forwardLoadedDurationMillis >= 0L)
        require(backwardLoadedDurationMillis >= 0L)
        require(trackSelectionDistance >= 0)
        require(maxPreloadBufferBytes > 0)
        require(userAgent.isNotBlank())
    }
}
