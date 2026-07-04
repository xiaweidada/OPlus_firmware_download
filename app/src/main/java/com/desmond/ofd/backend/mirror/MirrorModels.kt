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
 * Carried on a winning result so the download step can lazily resolve the token-gated proxy
 * URL. Public because it rides on the public `BackendOutcome.Success`.
 */
data class MirrorProxyRef(val deviceName: String, val otaVersion: String)

/**
 * Outcome of lazily resolving the mirror's token-gated proxy download URL. [Failed.reason] is a
 * human-readable explanation so the UI can show why a download/copy could not proceed instead of
 * silently doing nothing.
 */
sealed interface MirrorResolution {
    data class Resolved(val url: String) : MirrorResolution
    data class Failed(val reason: String) : MirrorResolution
}
