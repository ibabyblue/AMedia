package com.ibabyblue.amedia.core

import android.net.Uri
import java.util.UUID

/** A stable description of playable media. The URI is a location, not identity. */
public data class AMediaItem(
    public val mediaId: String,
    public val uri: Uri,
    public val mimeType: String? = null,
    public val metadata: AMediaMetadata = AMediaMetadata(),
    public val http: AMediaHttpConfiguration = AMediaHttpConfiguration(),
    public val cachePolicy: AMediaCachePolicy = AMediaCachePolicy.None,
    public val subtitles: List<AMediaSubtitle> = emptyList(),
    public val subtitlesEnabled: Boolean? = null,
    public val drm: AMediaDrmConfiguration? = null,
    public val backgroundPlaybackPolicy: AMediaBackgroundPlaybackPolicy =
        AMediaBackgroundPlaybackPolicy.Default,
    public val clipping: AMediaClippingConfiguration? = null,
    public val extras: Map<String, String> = emptyMap(),
) {
    init {
        require(mediaId.isNotBlank()) { "mediaId must not be blank" }
    }
}

public data class AMediaMetadata(
    public val title: String? = null,
    public val artist: String? = null,
    public val albumTitle: String? = null,
    public val artworkUri: Uri? = null,
    public val contentType: AMediaContentType = AMediaContentType.Unknown,
)

public enum class AMediaContentType {
    Unknown,
    Music,
    Speech,
    Video,
}

public data class AMediaHttpConfiguration(
    public val headers: Map<String, String> = emptyMap(),
    public val cacheKey: String? = null,
)

public enum class AMediaCachePolicy {
    None,
    ReadThrough,
}

public data class AMediaSubtitle(
    public val id: String,
    public val uri: Uri,
    public val mimeType: String,
    public val language: String? = null,
    public val label: String? = null,
    public val isDefault: Boolean = false,
    public val isForced: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "subtitle id must not be blank" }
        require(mimeType.isNotBlank()) { "subtitle mimeType must not be blank" }
    }
}

public data class AMediaDrmConfiguration(
    public val scheme: UUID,
    public val licenseUri: Uri? = null,
    public val requestHeaders: Map<String, String> = emptyMap(),
    public val multiSession: Boolean = false,
    public val forceDefaultLicenseUri: Boolean = false,
    public val offlineKeySetId: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AMediaDrmConfiguration) return false
        return scheme == other.scheme &&
            licenseUri == other.licenseUri &&
            requestHeaders == other.requestHeaders &&
            multiSession == other.multiSession &&
            forceDefaultLicenseUri == other.forceDefaultLicenseUri &&
            offlineKeySetId.contentEquals(other.offlineKeySetId)
    }

    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = 31 * result + (licenseUri?.hashCode() ?: 0)
        result = 31 * result + requestHeaders.hashCode()
        result = 31 * result + multiSession.hashCode()
        result = 31 * result + forceDefaultLicenseUri.hashCode()
        result = 31 * result + (offlineKeySetId?.contentHashCode() ?: 0)
        return result
    }
}

public data class AMediaClippingConfiguration(
    public val startPositionMillis: Long = 0L,
    public val endPositionMillis: Long? = null,
) {
    init {
        require(startPositionMillis >= 0L) { "startPositionMillis must be non-negative" }
        require(endPositionMillis == null || endPositionMillis > startPositionMillis) {
            "endPositionMillis must be greater than startPositionMillis"
        }
    }
}

public enum class AMediaBackgroundPlaybackPolicy {
    Default,
    Pause,
    Continue,
    ContinueAudio,
}
