package com.desmond.ofd.diag

import com.desmond.ofd.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

/**
 * The copy-pasteable failure report.
 *
 * Everything here is a plain value: these objects are retained inside download state for the
 * lifetime of a card, and they are built and rendered in unit tests, so they must not hold a
 * Context, a client, a lambda, or anything that touches `android.os.Build` / `android.net.Uri`
 * (both are unmocked stubs on the JVM). Callers pass in already-read values instead.
 *
 * Rendering ends with [redactMirrorIdentifiers], which is what keeps the secondary source out of
 * reports that users paste into public issues.
 */

/** Device facts worth having in a bug report. */
data class DeviceDiagnostics(
    val model: String,
    val marketName: String? = null,
    /** `ro.build.display.id`, e.g. `PLK110_16.0.9.400(CN01)`. */
    val firmwareVersion: String? = null,
    /** `ro.build.version.ota`. */
    val otaBuild: String? = null,
    /** `ro.build.version.oplusrom`, e.g. `V16.1.0`. */
    val colorOsVersion: String? = null,
    val ruiVersion: Int = 0,
    val androidRelease: String = "",
    val sdkInt: Int = 0,
    val region: String = "",
    val nvId: String? = null,
)

/** One backend's outcome, already reduced to text by the caller that owns the string resources. */
data class BackendDiagnostics(
    val label: String,
    val status: Status,
    val detail: String,
) {
    enum class Status { OK, FAIL, SKIPPED }
}

/** Everything known at the end of a firmware check. */
data class CheckDiagnostics(
    val appVersion: String = BuildConfig.VERSION_NAME,
    val appVersionCode: Int = BuildConfig.VERSION_CODE,
    val device: DeviceDiagnostics,
    val backends: List<BackendDiagnostics>,
    val winner: String? = null,
    /**
     * First 8 hex characters of the per-install id. Truncated deliberately: enough to correlate
     * with a server log, too short to reuse as somebody else's full client fingerprint.
     */
    val installId: String? = null,
)

/** A check, plus what was being downloaded when it failed. */
data class DownloadDiagnostics(
    val check: CheckDiagnostics,
    /** Backend plus CDN host — never a full URL, and never the secondary source's host. */
    val sourceLabel: String,
    val expectedSize: Long,
    val expectedMd5: String? = null,
    /** Already reduced by [redactSafUri]. */
    val targetLabel: String,
)

private const val REDACTED = "<secondary-source>"

/**
 * Values that must never appear in a report.
 *
 * Blank entries are dropped, and that filter is load-bearing rather than tidiness:
 * `String.replace("", x)` inserts `x` between every character, so a clone built without these
 * secrets would otherwise produce a report shredded into placeholder text.
 */
private val SECRETS: List<String> = buildList {
    listOf(
        BuildConfig.MIRROR_BASE_URL,
        BuildConfig.MIRROR_EMAIL,
        BuildConfig.MIRROR_KEY,
        BuildConfig.MIRROR_UA,
    ).forEach { value ->
        if (value.isNotBlank()) {
            add(value)
            value.toHttpUrlOrNull()?.host?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}.distinct().sortedByDescending { it.length }

/** A JWT, in case a token ever reaches a message; its payload carries the authorized email. */
private val JWT_RE = Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")

/**
 * Remove every trace of the secondary source from [text].
 *
 * Applied as a final whole-string pass rather than field by field, because the realistic leak is
 * exception text we never composed — `UnknownHostException: Unable to resolve host "…"`,
 * `unexpected end of stream on https://…` — which can reach a report through any error path.
 */
fun redactMirrorIdentifiers(text: String): String {
    val withoutSecrets = SECRETS.fold(text) { acc, secret -> acc.replace(secret, REDACTED, ignoreCase = true) }
    return JWT_RE.replace(withoutSecrets, "<token>")
}

/**
 * Reduce a SAF document URI to its provider and file name. The middle of the path is the user's
 * own folder structure and has no diagnostic value.
 */
fun redactSafUri(uri: String): String {
    if (uri.isBlank()) return "(none)"
    val afterScheme = uri.substringAfter("://", missingDelimiterValue = uri)
    val authority = afterScheme.substringBefore('/')
    val decoded = runCatching { URLDecoder.decode(afterScheme, "UTF-8") }.getOrDefault(afterScheme)
    val name = decoded.substringAfterLast('/').substringAfterLast(':').ifBlank { "(unnamed)" }
    return if (authority.isBlank() || authority == decoded) name else "$authority/…/$name"
}

/** Host of a URL, for naming a download source without exposing a signed link. */
fun hostOf(url: String?): String? = url?.toHttpUrlOrNull()?.host

fun CheckDiagnostics.render(): String = buildString {
    appendLine("OPlus Firmware $appVersion ($appVersionCode) — check details")
    appendLine()
    appendDevice(device)
    appendLine()
    appendBackends(backends, winner)
    installId?.let {
        appendLine()
        appendLine("Install id   $it")
    }
}.let(::redactMirrorIdentifiers)

fun DownloadDiagnostics.render(error: String): String = buildString {
    appendLine("OPlus Firmware ${check.appVersion} (${check.appVersionCode}) — download failure")
    appendLine()
    appendDevice(check.device)
    appendLine()
    appendBackends(check.backends, check.winner)
    appendLine()
    appendLine("Download")
    appendLine("  Source       $sourceLabel")
    appendLine("  Expected     ${if (expectedSize > 0) "$expectedSize bytes" else "unknown size"}" +
        (expectedMd5?.let { ", md5 $it" } ?: ", no md5"))
    appendLine("  Target       $targetLabel")
    check.installId?.let { appendLine("  Install id   $it") }
    appendLine()
    appendLine("Error")
    error.trim().lines().forEach { appendLine("  $it") }
}.let(::redactMirrorIdentifiers)

private fun StringBuilder.appendDevice(device: DeviceDiagnostics) {
    appendLine("Device")
    appendLine("  Model        ${device.model}${device.marketName?.let { "  ($it)" } ?: ""}")
    appendLine("  Firmware     ${device.firmwareVersion ?: "-"}")
    appendLine("  OTA build    ${device.otaBuild ?: "-"}")
    appendLine("  ColorOS      ${device.colorOsVersion ?: "-"}  (RUI ${device.ruiVersion})")
    appendLine("  Android      ${device.androidRelease} (SDK ${device.sdkInt})")
    appendLine("  Region       ${device.region}")
    appendLine("  NV id        ${device.nvId ?: "-"}")
}

private fun StringBuilder.appendBackends(backends: List<BackendDiagnostics>, winner: String?) {
    appendLine("Backends")
    val width = backends.maxOfOrNull { it.label.length } ?: 0
    backends.forEach {
        appendLine("  ${it.label.padEnd(width)}  ${it.status.name.padEnd(7)} ${it.detail}")
    }
    appendLine("  -> winner: ${winner ?: "none"}")
}
