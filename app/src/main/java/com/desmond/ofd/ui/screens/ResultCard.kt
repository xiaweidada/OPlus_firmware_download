package com.desmond.ofd.ui.screens

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.desmond.ofd.R
import com.desmond.ofd.backend.BackendId
import com.desmond.ofd.firmware.parseFirmwareUrlExpiresEpochSeconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ResultCard(
    state: HomeUiState.Result,
    onDownloadClick: () -> Unit,
    onCopied: (label: String) -> Unit,
    /** Mints a fresh shareable link, or null when one could not be produced. */
    onCopyUrl: suspend () -> String?,
    onCopyFailed: () -> Unit,
    /** Rendered on demand, so the report is never built for checks that went fine. */
    diagnosticsText: () -> String,
    modifier: Modifier = Modifier,
) {
    val winnerOutcome = state.winnerOutcome
    val md5Label = stringResource(R.string.md5)
    val downloadUrlLabel = stringResource(R.string.download_url)

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.latest_firmware),
                style = MaterialTheme.typography.labelLarge.copy(lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            if (winnerOutcome == null) {
                NoResultsBlock(state, diagnosticsText, onCopied)
            } else {
                Text(
                    text = firmwareVersion(winnerOutcome.versionName),
                    style = MaterialTheme.typography.titleLarge.copy(lineHeight = 32.sp),
                )

                Spacer(Modifier.height(16.dp))
                MetadataRow(
                    sizeBytes = winnerOutcome.sizeBytes,
                    buildDate = extractBuildDate(winnerOutcome),
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Md5Row(
                    md5 = winnerOutcome.md5,
                    onCopy = { onCopied(md5Label) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Spacer(Modifier.height(12.dp))
                BackendsBreakdown(state)

                Spacer(Modifier.height(12.dp))
                val clipboard = LocalClipboard.current
                val scope = rememberCoroutineScope()
                var copiedLinkExpiresAt by remember(winnerOutcome) { mutableStateOf<Long?>(null) }
                var copying by remember(winnerOutcome) { mutableStateOf(false) }

                // The countdown belongs to the *copied* link and starts when that link is minted.
                // Showing a check-time countdown would tick down against a URL nobody is using —
                // the Download button mints its own and is unaffected by this expiring.
                copiedLinkExpiresAt?.let { expiresAt ->
                    ExpiryRow(expiresAt = expiresAt)
                    Spacer(Modifier.height(12.dp))
                }

                Button(
                    onClick = onDownloadClick,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.download_with_size, formatBytes(winnerOutcome.sizeBytes)))
                }
                // Every backend can now produce a shareable link on demand, including the mirror:
                // what gets copied is the CDN's own pre-signed URL, which says nothing about which
                // backend found it.
                Spacer(Modifier.height(4.dp))
                TextButton(
                    enabled = !copying,
                    onClick = {
                        scope.launch {
                            copying = true
                            val url = try { onCopyUrl() } finally { copying = false }
                            if (url.isNullOrBlank()) {
                                onCopyFailed()
                                return@launch
                            }
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText(downloadUrlLabel, url)),
                            )
                            copiedLinkExpiresAt = parseFirmwareUrlExpiresEpochSeconds(url)
                            onCopied(downloadUrlLabel)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Minting a link is a network round trip — for the mirror, two. A disabled
                    // button alone reads as "broken", so show that work is happening.
                    if (copying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (copying) R.string.copy_url_working else R.string.copy_url))
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(sizeBytes: Long, buildDate: String?) {
    Row(modifier = Modifier.fillMaxWidth()) {
        MetaCell(
            label = stringResource(R.string.size),
            value = formatBytes(sizeBytes),
            modifier = Modifier.weight(1f),
        )
        MetaCell(
            label = stringResource(R.string.build_date),
            value = buildDate ?: "—",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetaCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun Md5Row(md5: String?, onCopy: (String) -> Unit) {
    if (md5.isNullOrBlank()) return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val md5Label = stringResource(R.string.md5)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable {
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText(md5Label, md5)),
                    )
                    onCopy(md5)
                }
            }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.md5),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = md5,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = stringResource(R.string.copy_md5),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ExpiryRow(expiresAt: Long, modifier: Modifier = Modifier) {
    val now by produceState(initialValue = System.currentTimeMillis() / 1000L) {
        while (true) {
            delay(1000L)
            value = System.currentTimeMillis() / 1000L
        }
    }
    val secondsLeft = (expiresAt - now).coerceAtLeast(0L)
    val isExpired = secondsLeft == 0L
    val color = if (isExpired) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isExpired) Icons.Outlined.ErrorOutline else Icons.Outlined.Schedule,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        val text = when {
            isExpired -> stringResource(R.string.url_expired_recheck)
            secondsLeft >= 60 -> stringResource(
                R.string.url_valid_minutes_seconds,
                secondsLeft / 60,
                secondsLeft % 60,
            )
            else -> stringResource(R.string.url_valid_seconds, secondsLeft)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun BackendsBreakdown(state: HomeUiState.Result) {
    // An unconfigured mirror is absent, not failing. A row that could only ever fail reads as a
    // bug in a build that never had the source to begin with.
    val rows = state.outcomes().filterNot { (id, outcome) ->
        id == BackendId.MIRROR && outcome == BackendOutcome.NotAttempted
    }
    rows.forEachIndexed { index, (id, outcome) ->
        if (index > 0) Spacer(Modifier.height(6.dp))
        BackendRow(
            label = stringResource(backendLabelRes(id)),
            outcome = outcome,
            isWinner = state.winner == id,
        )
    }
}

@StringRes
private fun backendLabelRes(id: BackendId): Int = when (id) {
    BackendId.REALME_OTA -> R.string.backend_label_realme_ota
    BackendId.DANIELSPRINGER -> R.string.backend_label_danielspringer
    BackendId.MIRROR -> R.string.backend_label_mirror
}

@Composable
private fun BackendRow(label: String, outcome: BackendOutcome, isWinner: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (outcome) {
            is BackendOutcome.Success -> Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = if (isWinner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            is BackendOutcome.Failure, BackendOutcome.NotAttempted -> Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val detail = when (outcome) {
                is BackendOutcome.Success -> {
                    val ver = firmwareVersion(outcome.versionName)
                    if (isWinner) {
                        stringResource(R.string.backend_newest, ver)
                    } else {
                        stringResource(R.string.backend_older, ver)
                    }
                }
                is BackendOutcome.Failure -> backendMessageText(outcome.message)
                BackendOutcome.NotAttempted -> stringResource(R.string.not_run)
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun backendMessageText(message: BackendMessage): String = when (message) {
    is BackendMessage.Resource -> stringResource(
        message.resId,
        *message.args.toTypedArray(),
    )
    is BackendMessage.Raw -> message.value
}

@Composable
private fun NoResultsBlock(
    state: HomeUiState.Result,
    diagnosticsText: () -> String,
    onCopied: (String) -> Unit,
) {
    Text(
        text = stringResource(R.string.no_firmware_found),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
    )
    Spacer(Modifier.height(8.dp))
    BackendsBreakdown(state)

    // Offered only when nothing was found, which is the case where the user has no other way to
    // tell us what happened. On a successful check this would be permanent clutter beside the
    // primary action — and with the mirror as its own row, a failing backend is routine for any
    // model it does not carry.
    Spacer(Modifier.height(12.dp))
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.copy_diagnostics)
    OutlinedButton(
        onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, diagnosticsText())))
                onCopied(label)
            }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

private fun firmwareVersion(versionName: String): String =
    versionName.substringAfter('_', missingDelimiterValue = versionName)

private fun extractBuildDate(outcome: BackendOutcome.Success): String? {
    // The dated path segment only exists in the *resolved* CDN link, never in a gate URL.
    Regex("""/component-ota/(\d{2})/(\d{2})/(\d{2})/""").find(outcome.displayUrl.orEmpty())?.let {
        val (y, m, d) = it.destructured
        return "20$y-$m-$d"
    }
    val withTimestamp = outcome.realOtaVersion ?: outcome.versionName
    Regex("""_(\d{4})(\d{2})(\d{2})""").find(withTimestamp)?.let {
        val (y, m, d) = it.destructured
        return "$y-$m-$d"
    }
    return null
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes >= 1L shl 30 -> "%.2f GiB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MiB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KiB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
