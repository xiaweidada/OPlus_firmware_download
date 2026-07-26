package com.desmond.ofd.backend.mirror

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One catalog entry from `GET api/coloros/devices/`. */
@Serializable
internal data class MirrorDeviceModel(
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("device_model") val deviceModel: String = "",
)

/** One firmware version from `GET api/coloros/fullVersions/{device}`. */
@Serializable
internal data class MirrorVersionDto(
    /** Comparable display version, e.g. `PLK110_16.0.9.400(CN01)`. */
    @SerialName("rom_version") val romVersion: String = "",
    /** Internal build id, e.g. `PLK110_11.A.68_0680_202606250030`. */
    @SerialName("ota_version") val otaVersion: String = "",
    @SerialName("size_bytes") val sizeBytes: String = "",
    @SerialName("md5") val md5: String = "",
    @SerialName("security_patch") val securityPatch: String = "",
    @SerialName("is_latest") val isLatest: Int = 0,
    @SerialName("error") val error: String? = null,
)

/** Body for `POST api/coloros/premium/download-token`. */
@Serializable
internal data class MirrorTokenRequest(
    val email: String,
    val device: String,
    @SerialName("ota_version") val otaVersion: String,
    @SerialName("package_type") val packageType: String,
)

/** Response of `POST api/coloros/premium/download-token`. */
@Serializable
internal data class MirrorTokenResponse(
    val token: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
)

/** Response of `GET api/coloros/download/{device}/{otaVersion}`. */
@Serializable
internal data class MirrorDownloadLinks(
    @SerialName("full_url") val fullUrl: String = "",
    @SerialName("incremental_url") val incrementalUrl: String = "",
    val message: String = "",
)

/** Resolved latest full-package metadata (no download URL yet — that is token-gated). */
data class MirrorVersion(
    val deviceName: String,
    /** Comparable display version, matches `DanielspringerResult.displayName`. */
    val versionName: String,
    val otaVersion: String,
    val sizeBytes: Long,
    val md5: String?,
    val securityPatch: String?,
)

/**
 * Why the mirror could not supply metadata.
 *
 * An enum rather than a message on purpose. The natural message here is an exception string like
 * `UnknownHostException: Unable to resolve host "…"`, which would print the mirror's domain into
 * the UI and from there into screenshots and shared bug reports. Callers render these through the
 * string layer instead, so no code path can leak the host by accident.
 */
enum class MirrorUnavailableReason {
    /** No base URL / key / user-agent in this build — an open-source clone, typically. */
    NOT_CONFIGURED,

    /** The mirror does not carry this model. Expected: its catalog is largely CN-market devices. */
    MODEL_NOT_COVERED,

    /** Reachable-but-broken, or not reachable at all. */
    UNAVAILABLE,
}

/** Metadata lookup outcome. */
sealed interface MirrorLookup {
    data class Found(val version: MirrorVersion) : MirrorLookup
    data class Unavailable(val reason: MirrorUnavailableReason) : MirrorLookup
}

/** Why a download link could not be minted. Enum for the same reason as [MirrorUnavailableReason]. */
enum class MirrorDownloadFailure {
    NOT_CONFIGURED,

    /** This build carries no authorized email, so the source will not issue download tokens. */
    NO_AUTHORIZED_EMAIL,

    /** The firmware has no OTA build id, which the proxy addresses packages by. */
    NO_OTA_VERSION,

    /** The token request was refused — unauthorized email, or rate limited. */
    TOKEN_REJECTED,

    /** Token accepted but no usable link came back. */
    NO_LINK,
}

/** Outcome of resolving a download link from the mirror. */
sealed interface MirrorResolution {
    data class Resolved(val url: String) : MirrorResolution
    data class Failed(val reason: MirrorDownloadFailure) : MirrorResolution
}
