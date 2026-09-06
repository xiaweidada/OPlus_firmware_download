package com.desmond.ofd.ui.screens

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.desmond.ofd.R
import com.desmond.ofd.backend.BackendId
import com.desmond.ofd.backend.danielspringer.DanielspringerLookup
import com.desmond.ofd.backend.danielspringer.DanielspringerSource
import com.desmond.ofd.backend.mirror.MirrorClient
import com.desmond.ofd.backend.mirror.MirrorConfig
import com.desmond.ofd.backend.mirror.MirrorDownloadFailure
import com.desmond.ofd.backend.mirror.MirrorLookup
import com.desmond.ofd.backend.mirror.MirrorResolution
import com.desmond.ofd.backend.mirror.MirrorUnavailableReason
import com.desmond.ofd.backend.pickWinner
import com.desmond.ofd.backend.realmeota.data.OtaRequestParams
import com.desmond.ofd.backend.realmeota.network.OtaResult
import com.desmond.ofd.backend.realmeota.network.RealmeOtaDownloadFailure
import com.desmond.ofd.backend.realmeota.network.RealmeOtaDownloadSelection
import com.desmond.ofd.backend.realmeota.network.RealmeOtaDownloadSelector
import com.desmond.ofd.backend.realmeota.network.RealmeOtaVersionCandidates
import com.desmond.ofd.backend.realmeota.network.RealmeOtaClient
import com.desmond.ofd.device.DeviceProps
import com.desmond.ofd.device.DeviceSnapshot
import com.desmond.ofd.device.toDiagnostics
import com.desmond.ofd.download.DownloadCoordinator
import com.desmond.ofd.download.DownloadParams
import com.desmond.ofd.download.DownloadUrlProvider
import com.desmond.ofd.diag.BackendDiagnostics
import com.desmond.ofd.diag.CheckDiagnostics
import com.desmond.ofd.diag.DeviceDiagnostics
import com.desmond.ofd.diag.DownloadDiagnostics
import com.desmond.ofd.diag.hostOf
import com.desmond.ofd.diag.redactSafUri
import com.desmond.ofd.firmware.FirmwareSource
import com.desmond.ofd.firmware.FirmwareUrlProbe
import com.desmond.ofd.firmware.firmwareSourceFor
import com.desmond.ofd.firmware.FirmwareUrlProbeResult
import com.desmond.ofd.firmware.formatFirmwareBytes
import com.desmond.ofd.firmware.parseFirmwareUrlExpiresEpochSeconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface BackendMessage {
    data class Resource(
        @param:StringRes val resId: Int,
        val args: List<String> = emptyList(),
    ) : BackendMessage
    data class Raw(val value: String) : BackendMessage
}

sealed interface BackendOutcome {
    data class Success(
        /** Comparable display version, e.g. `PLK110_16.0.7.206(CN01)` (numeric, dotted). */
        val versionName: String,
        /** Durable handle used to (re)mint a download link — see [FirmwareSource]. */
        val source: FirmwareSource,
        /**
         * The link as resolved during the check, for display and Copy only. Pre-signed and
         * short-lived, so it must never be what the downloader uses; null when the source was
         * not resolved at check time (the mirror defers it until the user actually asks).
         */
        val displayUrl: String? = null,
        val sizeBytes: Long,
        val md5: String?,
        val securityPatch: String? = null,
        val expiresAtEpochSeconds: Long? = null,
        /**
         * Optional internal OPPO OTA version like `PLK110_11.A.63_0630_202605061316` —
         * carries the build timestamp but contains letters so it's NOT comparable. Used
         * for build-date extraction and display only.
         */
        val realOtaVersion: String? = null,
    ) : BackendOutcome
    data class Failure(val message: BackendMessage) : BackendOutcome
    data object NotAttempted : BackendOutcome
}

/** Result of asking the ViewModel to begin a download. */
sealed interface DownloadStart {
    data class Started(val id: String) : DownloadStart
    data object AlreadyRunning : DownloadStart
    data class Failed(val reason: String) : DownloadStart
}

internal data class PendingDownload(
    val outcome: BackendOutcome.Success,
    val displayName: String,
    val check: CheckDiagnostics,
)

sealed interface HomeUiState {
    data object Idle : HomeUiState
    data object Loading : HomeUiState
    data class Result(
        val marketingName: String,
        /** Device facts captured at check time, for the diagnostics report. */
        val device: DeviceDiagnostics,
        val realmeOta: BackendOutcome,
        val danielspringer: BackendOutcome,
        val mirror: BackendOutcome,
        /** The source that won the version comparison; null when nothing succeeded. */
        val winner: BackendId?,
        val winnerOutcome: BackendOutcome.Success?,
    ) : HomeUiState {
        /** Backends in display order, paired with their outcome. */
        fun outcomes(): List<Pair<BackendId, BackendOutcome>> = listOf(
            BackendId.REALME_OTA to realmeOta,
            BackendId.DANIELSPRINGER to danielspringer,
            BackendId.MIRROR to mirror,
        )
    }
    data class Error(val message: String) : HomeUiState
}


class HomeViewModel(
    application: Application,
    private val realmeOtaClient: RealmeOtaClient = RealmeOtaClient(),
    private val danielspringerSource: DanielspringerSource = DanielspringerSource(application),
    private val mirrorClient: MirrorClient = MirrorClient(application),
    private val getSnapshot: () -> DeviceSnapshot = { DeviceProps.snapshot(useShellFallback = true) },
    private val enqueueDownload: (Application, DownloadParams) -> String? = { app, params ->
        DownloadCoordinator.start(app, params)
    },
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    private val firmwareUrlProbe = FirmwareUrlProbe()
    // The document picker can recreate the activity. Keep its selection with the ViewModel,
    // including the check that produced it, so the result and its diagnostics stay together.
    internal var pendingDownload: PendingDownload? = null

    /**
     * Tracks the in-flight check coroutine so [reset] can cancel it before it overwrites the
     * freshly-cleared state with a stale Result.
     */
    private var checkJob: Job? = null

    fun checkAuto() {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _state.value = HomeUiState.Loading
            val snapshot = readAutoSnapshot()
                ?: return@launch fail(R.string.error_read_device_properties)
            val params = OtaRequestParams(
                model = snapshot.productName,
                otaVersion = snapshot.otaVersion.orEmpty(),
                ruiVersion = snapshot.ruiVersion,
                nvIdentifier = snapshot.nvId,
                region = snapshot.region,
            )
            runCheck(params, snapshot)
        }
    }

    fun reset() {
        checkJob?.cancel()
        checkJob = null
        _state.value = HomeUiState.Idle
    }

    /**
     * Start foreground work immediately after the picker returns. Token exchange and link
     * verification run in that job, so leaving the activity during a slow request cannot turn
     * the later service launch into a forbidden background start. Resolution errors appear on
     * the download card with the original check's diagnostics.
     */
    fun startDownload(
        targetUri: Uri,
        outcome: BackendOutcome.Success,
        displayName: String,
        check: CheckDiagnostics? = (_state.value as? HomeUiState.Result)?.let(::checkDiagnostics),
    ): DownloadStart {
        val provider = urlProviderFor(outcome.source)
        val params = DownloadParams(
            urlProvider = provider,
            targetUri = targetUri,
            displayName = displayName,
            expectedSize = outcome.sizeBytes,
            expectedMd5 = outcome.md5,
            diagnostics = check?.let { captured ->
                DownloadDiagnostics(
                    check = captured,
                    sourceLabel = listOfNotNull(captured.winner, hostOf(outcome.displayUrl)).joinToString(" / "),
                    expectedSize = outcome.sizeBytes,
                    expectedMd5 = outcome.md5,
                    targetLabel = redactSafUri(targetUri.toString()),
                )
            },
        )
        return try {
            enqueueDownload(getApplication(), params)
                ?.let { DownloadStart.Started(it) }
                ?: DownloadStart.AlreadyRunning
        } catch (e: RuntimeException) {
            DownloadStart.Failed(e.message ?: "could not start the download service")
        }
    }

    /**
     * Mint a link the user can paste into a browser or download manager.
     *
     * Always resolved fresh. By the time someone presses Copy the check-time link is usually
     * minutes old, and handing out an expired URL reads as a broken app rather than an expired
     * link — especially now that the Download button mints its own and keeps working.
     *
     * Returns null when no shareable link could be produced.
     */
    suspend fun resolveShareableUrl(outcome: BackendOutcome.Success): String? {
        val raw = try {
            urlProviderFor(outcome.source)()
        } catch (_: IOException) {
            return null
        }
        // A gate URL is useless outside the app (it needs the anti-leech header), so resolve it
        // through to the CDN link that anything else can actually fetch.
        return when (val probe = firmwareUrlProbe.probe(raw, outcome.sizeBytes, expectedMd5 = outcome.md5)) {
            is FirmwareUrlProbeResult.Success -> probe.resolvedUrl
            is FirmwareUrlProbeResult.Failure -> null
        }
    }

    /**
     * Turn a source into something that yields a fresh link on every call. A [FirmwareSource.Gate]
     * simply returns its gate URL — the download engine performs the anti-leech hop, so each pass
     * through it produces a newly signed link.
     */
    private fun urlProviderFor(source: FirmwareSource): DownloadUrlProvider = when (source) {
        is FirmwareSource.Direct -> ({ source.url })
        is FirmwareSource.Gate -> ({ source.gateUrl })
        is FirmwareSource.MirrorProxy -> ({ resolveMirrorUrl(source) })
    }

    private suspend fun resolveMirrorUrl(source: FirmwareSource.MirrorProxy): String =
        when (val resolution = mirrorClient.resolveDownloadUrl(source.deviceName, source.otaVersion)) {
            is MirrorResolution.Resolved -> resolution.url
            is MirrorResolution.Failed -> throw IOException(mirrorFailureMessage(resolution.reason))
        }

    /** Everything a bug report needs about this check. */
    fun checkDiagnostics(state: HomeUiState.Result): CheckDiagnostics = CheckDiagnostics(
        device = state.device,
        backends = state.outcomes().map { (id, outcome) ->
            BackendDiagnostics(
                label = backendLabel(id),
                status = when (outcome) {
                    is BackendOutcome.Success -> BackendDiagnostics.Status.OK
                    is BackendOutcome.Failure -> BackendDiagnostics.Status.FAIL
                    BackendOutcome.NotAttempted -> BackendDiagnostics.Status.SKIPPED
                },
                detail = when (outcome) {
                    is BackendOutcome.Success -> outcome.versionName
                    is BackendOutcome.Failure -> backendMessageText(outcome.message)
                    BackendOutcome.NotAttempted ->
                        getApplication<Application>().getString(R.string.not_run)
                },
            )
        },
        winner = state.winner?.let(::backendLabel),
        installId = installId(),
    )

    private fun backendLabel(id: BackendId): String = getApplication<Application>().getString(
        when (id) {
            BackendId.REALME_OTA -> R.string.backend_label_realme_ota
            BackendId.DANIELSPRINGER -> R.string.backend_label_danielspringer
            BackendId.MIRROR -> R.string.backend_label_mirror
        },
    )

    private fun backendMessageText(message: BackendMessage): String = when (message) {
        is BackendMessage.Raw -> message.value
        is BackendMessage.Resource -> getApplication<Application>()
            .getString(message.resId, *message.args.toTypedArray())
    }

    /**
     * Short per-install id, present only when the secondary source is configured (it is that
     * source's own identifier). Truncated: enough to find in a server log, too short to replay
     * as somebody else's full client fingerprint in a report pasted somewhere public.
     */
    private fun installId(): String? =
        if (MirrorConfig.isConfigured) mirrorClient.fingerprint.take(8) else null

    /** Render a mirror failure through the string layer, so no code path can print its host. */
    private fun mirrorFailureMessage(reason: MirrorDownloadFailure): String =
        getApplication<Application>().getString(
            when (reason) {
                MirrorDownloadFailure.NOT_CONFIGURED -> R.string.mirror_error_not_configured
                MirrorDownloadFailure.NO_AUTHORIZED_EMAIL -> R.string.mirror_error_no_authorized_email
                MirrorDownloadFailure.NO_OTA_VERSION -> R.string.mirror_error_no_ota_version
                MirrorDownloadFailure.TOKEN_REJECTED -> R.string.mirror_error_token_rejected
                MirrorDownloadFailure.NO_LINK -> R.string.mirror_error_no_link
            },
        )

    private suspend fun runCheck(params: OtaRequestParams, snapshot: DeviceSnapshot) {
        // Each branch is wrapped: these run as siblings under one coroutineScope, so an uncaught
        // throw in any of them would cancel the others, leave the UI stuck on Loading, and escape
        // viewModelScope into a crash.
        val (realme, springer, mirror) = coroutineScope {
            val realmeDeferred = async { guarded { runRealmeOta(params) } }
            val springerDeferred = async { guarded { danielspringerOutcome(params) } }
            val mirrorDeferred = async { guarded { mirrorOutcome(params) } }
            Triple(realmeDeferred.await(), springerDeferred.await(), mirrorDeferred.await())
        }
        val byBackend = mapOf(
            BackendId.REALME_OTA to realme,
            BackendId.DANIELSPRINGER to springer,
            BackendId.MIRROR to mirror,
        )
        val winner = pickWinner(
            byBackend.mapNotNull { (id, outcome) ->
                (outcome as? BackendOutcome.Success)?.let { id to it.versionName }
            },
        )
        _state.value = HomeUiState.Result(
            marketingName = snapshot.marketName ?: params.model,
            device = snapshot.toDiagnostics(),
            realmeOta = realme,
            danielspringer = springer,
            mirror = mirror,
            winner = winner,
            winnerOutcome = winner?.let { byBackend[it] as? BackendOutcome.Success },
        )
    }

    /** Run one backend, turning any escape into a failure row rather than a dead check. */
    private suspend fun guarded(block: suspend () -> BackendOutcome): BackendOutcome =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            BackendOutcome.Failure(
                BackendMessage.Raw(t.message ?: t::class.simpleName ?: "unknown error"),
            )
        }

    private suspend fun readAutoSnapshot(): DeviceSnapshot? {
        var last: DeviceSnapshot? = null
        repeat(AUTO_SNAPSHOT_ATTEMPTS) { attempt ->
            val snapshot = withContext(Dispatchers.IO) {
                runCatching { getSnapshot() }.getOrNull()
            }
            if (snapshot != null) {
                last = snapshot
                if (!snapshot.otaVersion.isNullOrBlank()) return snapshot
            }
            if (attempt < AUTO_SNAPSHOT_ATTEMPTS - 1) {
                delay(AUTO_SNAPSHOT_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return last
    }

    private fun fail(@StringRes messageResId: Int) {
        _state.value = HomeUiState.Error(getApplication<Application>().getString(messageResId))
    }

    private suspend fun runRealmeOta(params: OtaRequestParams): BackendOutcome {
        var bestOutcome: BackendOutcome? = null
        for (candidate in realmeOtaQueryCandidates(params)) {
            val outcome = runRealmeOtaOnce(candidate)
            if (outcome is BackendOutcome.Success) return outcome
            bestOutcome = preferRealmeFailure(bestOutcome, outcome)
        }
        return bestOutcome ?: BackendOutcome.Failure(backendMessage(R.string.backend_error_unknown))
    }

    private suspend fun runRealmeOtaOnce(params: OtaRequestParams): BackendOutcome =
        when (val r = realmeOtaClient.query(params)) {
            is OtaResult.Success -> preflightRealmeOtaSuccess(r)
            is OtaResult.HttpError -> BackendOutcome.Failure(
                backendMessage(
                    R.string.backend_error_http,
                    r.code.toString(),
                    r.errMsg ?: getApplication<Application>().getString(R.string.backend_error_no_message),
                ),
            )
            is OtaResult.NetworkError -> BackendOutcome.Failure(
                backendMessage(
                    R.string.backend_error_network,
                    r.cause.message ?: r.cause::class.simpleName ?: getApplication<Application>().getString(R.string.backend_error_unknown),
                ),
            )
            is OtaResult.CryptoError -> BackendOutcome.Failure(
                backendMessage(
                    R.string.backend_error_crypto,
                    r.cause.message ?: r.cause::class.simpleName ?: getApplication<Application>().getString(R.string.backend_error_unknown),
                ),
            )
            is OtaResult.ContentError -> BackendOutcome.Failure(
                backendMessage(R.string.backend_error_content, r.checkFailReason),
            )
        }

    private suspend fun preflightRealmeOtaSuccess(result: OtaResult.Success): BackendOutcome {
        return when (val selected = RealmeOtaDownloadSelector.select(result.response)) {
            is RealmeOtaDownloadSelection.Success -> preflightSelectedRealmeOta(selected)
            is RealmeOtaDownloadSelection.Failure -> mapRealmeSelectionFailure(selected)
        }
    }

    private suspend fun preflightSelectedRealmeOta(selected: RealmeOtaDownloadSelection.Success): BackendOutcome {
        var lastFailure: FirmwareUrlProbeResult.Failure? = null
        for (url in selected.downloadUrls) {
            when (val probe = firmwareUrlProbe.probe(url, expectedSize = selected.sizeBytes, expectedMd5 = selected.md5)) {
                // Keep the URL that actually worked, not the resolved one: it is the gate, and
                // re-hitting it is what lets the download survive its own signature expiring.
                is FirmwareUrlProbeResult.Success -> return BackendOutcome.Success(
                    versionName = selected.versionName,
                    realOtaVersion = selected.realOtaVersion,
                    source = firmwareSourceFor(url),
                    displayUrl = probe.resolvedUrl,
                    sizeBytes = probe.totalSize,
                    md5 = selected.md5 ?: probe.md5,
                    securityPatch = selected.securityPatch,
                    expiresAtEpochSeconds = parseFirmwareUrlExpiresEpochSeconds(probe.resolvedUrl),
                )
                is FirmwareUrlProbeResult.Failure -> lastFailure = probe
            }
        }
        val failure = lastFailure
            ?: return BackendOutcome.Failure(backendMessage(R.string.backend_error_no_download_url))
        return BackendOutcome.Failure(
            when {
                failure.rejectionCode != null -> backendMessage(
                    R.string.backend_error_antileech_rejected,
                    failure.rejectionCode,
                )
                failure.observedSize != null -> backendMessage(
                    R.string.backend_error_invalid_firmware_size,
                    formatFirmwareBytes(failure.observedSize),
                )
                else -> backendMessage(
                    R.string.backend_error_download_link_unverified,
                    failure.detail,
                )
            },
        )
    }

    private fun mapRealmeSelectionFailure(selected: RealmeOtaDownloadSelection.Failure): BackendOutcome.Failure =
        BackendOutcome.Failure(
            when (selected.reason) {
                RealmeOtaDownloadFailure.NO_DOWNLOAD_PACKET ->
                    backendMessage(R.string.backend_error_no_download_packet)
                RealmeOtaDownloadFailure.NO_DOWNLOAD_URL ->
                    backendMessage(R.string.backend_error_no_download_url)
                RealmeOtaDownloadFailure.INVALID_FIRMWARE_SIZE ->
                    backendMessage(
                        R.string.backend_error_invalid_firmware_size,
                        formatFirmwareBytes(selected.observedSize ?: -1L),
                    )
                RealmeOtaDownloadFailure.GKA_ATTESTATION_REQUIRED ->
                    backendMessage(R.string.backend_error_gka_required)
            },
        )

    private fun realmeOtaQueryCandidates(params: OtaRequestParams): List<OtaRequestParams> {
        return RealmeOtaVersionCandidates.versions(params.model, params.otaVersion)
            .map { params.copy(otaVersion = it) }
    }

    private fun preferRealmeFailure(current: BackendOutcome?, candidate: BackendOutcome): BackendOutcome {
        if (current !is BackendOutcome.Failure) return candidate
        if (candidate !is BackendOutcome.Failure) return current
        return if (realmeFailurePriority(candidate) > realmeFailurePriority(current)) candidate else current
    }

    private fun realmeFailurePriority(outcome: BackendOutcome.Failure): Int {
        val message = outcome.message
        if (message is BackendMessage.Resource) {
            return when (message.resId) {
                R.string.backend_error_antileech_rejected -> 60
                R.string.backend_error_download_link_unverified -> 50
                R.string.backend_error_invalid_firmware_size -> 45
                R.string.backend_error_network -> 40
                R.string.backend_error_crypto -> 35
                R.string.backend_error_no_download_packet,
                R.string.backend_error_no_download_url
                -> 30
                R.string.backend_error_content -> 20
                R.string.backend_error_http -> {
                    val code = message.args.firstOrNull()
                    val detail = message.args.getOrNull(1).orEmpty()
                    if (code == "2004" && detail.contains("Result is empty", ignoreCase = true)) 10 else 25
                }
                else -> 20
            }
        }
        return 20
    }

    private suspend fun danielspringerOutcome(params: OtaRequestParams): BackendOutcome =
        when (val found = danielspringerSource.latest(params.model, params.region)) {
            is DanielspringerLookup.Found -> BackendOutcome.Success(
                versionName = found.versionName,
                realOtaVersion = found.realOtaVersion,
                source = found.source,
                displayUrl = found.displayUrl,
                sizeBytes = found.sizeBytes,
                md5 = found.md5,
                securityPatch = found.securityPatch,
                expiresAtEpochSeconds = found.expiresAtEpochSeconds,
            )
            is DanielspringerLookup.Failed -> BackendOutcome.Failure(BackendMessage.Raw(found.detail))
        }

    private suspend fun mirrorOutcome(params: OtaRequestParams): BackendOutcome =
        when (val lookup = mirrorClient.resolveLatest(params.model)) {
            is MirrorLookup.Found -> BackendOutcome.Success(
                versionName = lookup.version.versionName,
                realOtaVersion = lookup.version.otaVersion,
                // Deliberately unresolved during the check: each resolution spends a download
                // token, and most checks never become a download.
                source = FirmwareSource.MirrorProxy(lookup.version.deviceName, lookup.version.otaVersion),
                displayUrl = null,
                sizeBytes = lookup.version.sizeBytes,
                md5 = lookup.version.md5,
                securityPatch = lookup.version.securityPatch,
            )
            is MirrorLookup.Unavailable -> when (lookup.reason) {
                // An unconfigured build has no mirror at all; the row is hidden rather than shown
                // as a permanent failure.
                MirrorUnavailableReason.NOT_CONFIGURED -> BackendOutcome.NotAttempted
                MirrorUnavailableReason.MODEL_NOT_COVERED ->
                    BackendOutcome.Failure(backendMessage(R.string.backend_error_model_not_covered))
                MirrorUnavailableReason.UNAVAILABLE ->
                    BackendOutcome.Failure(backendMessage(R.string.backend_error_source_unavailable))
            }
        }

    companion object {
        private const val AUTO_SNAPSHOT_ATTEMPTS = 3
        private const val AUTO_SNAPSHOT_RETRY_DELAY_MS = 150L

        private fun backendMessage(@StringRes resId: Int, vararg args: String): BackendMessage =
            BackendMessage.Resource(resId, args.toList())

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("HomeViewModel requires Application")
                return HomeViewModel(app) as T
            }
        }
    }
}
