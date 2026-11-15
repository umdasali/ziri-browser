package com.ziribrowser.ui.browser

import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ziribrowser.browser.ConnectionInfo
import com.ziribrowser.browser.SslProblem
import com.ziribrowser.browser.TabState
import com.ziribrowser.browser.UrlUtils
import com.ziribrowser.ui.GlassSurface

/** How trustworthy the current page's connection is. */
enum class SecurityLevel { NONE, SECURE, NOT_SECURE, WARNING }

fun TabState.securityLevel(): SecurityLevel = when {
    url.isEmpty() -> SecurityLevel.NONE
    sslProblem != null -> SecurityLevel.WARNING
    certOverrideHost != null && UrlUtils.rawHost(url) == certOverrideHost -> SecurityLevel.WARNING
    url.startsWith("https://", ignoreCase = true) -> SecurityLevel.SECURE
    url.startsWith("http://", ignoreCase = true) -> SecurityLevel.NOT_SECURE
    else -> SecurityLevel.NONE
}

/** Lock (or warning) shown at the start of the address bar. Tapping it opens the connection details. */
@Composable
fun SecurityIndicator(level: SecurityLevel, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tint, label) = when (level) {
        SecurityLevel.SECURE -> Triple(Icons.Rounded.Lock, scheme.primary, null)
        SecurityLevel.NOT_SECURE -> Triple(Icons.Rounded.LockOpen, scheme.error, stringResource(R.string.not_secure))
        SecurityLevel.WARNING -> Triple(Icons.Rounded.Warning, scheme.error, stringResource(R.string.warning))
        SecurityLevel.NONE -> Triple(Icons.Rounded.Search, scheme.onSurfaceVariant, null)
    }
    val modifier = if (level == SecurityLevel.NONE) Modifier else Modifier.clickableNoRipple(onClick)
    Row(modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, if (level == SecurityLevel.NONE) null else stringResource(R.string.connection_details), tint = tint, modifier = Modifier.size(20.dp))
        if (label != null) Text(label, color = tint, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick))

/** Explains the page's connection in plain words. */
@Composable
fun ConnectionDialog(tab: TabState, info: ConnectionInfo?, onDismiss: () -> Unit) {
    val level = tab.securityLevel()
    val host = UrlUtils.host(tab.url)
    val title = when (level) {
        SecurityLevel.SECURE -> stringResource(R.string.connection_is_secure)
        SecurityLevel.NOT_SECURE -> stringResource(R.string.connection_is_not_secure)
        else -> stringResource(R.string.certificate_warning)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (level == SecurityLevel.SECURE) Icons.Rounded.Lock else Icons.Rounded.Warning, null,
                tint = if (level == SecurityLevel.SECURE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(host, fontWeight = FontWeight.SemiBold)
                when (level) {
                    SecurityLevel.SECURE -> Text(stringResource(R.string.what_you_send_to_and_receive_from_this))
                    SecurityLevel.NOT_SECURE -> Text(stringResource(R.string.this_site_doesn_t_use_https_other_peop))
                    else -> Text(stringResource(R.string.you_chose_to_open_this_site_even_thoug))
                }
                if (info != null && (info.issuedBy.isNotEmpty() || info.validTo.isNotEmpty())) {
                    Text(stringResource(R.string.certificate), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp))
                    if (info.issuedTo.isNotEmpty()) Detail(stringResource(R.string.issued_to), info.issuedTo)
                    if (info.issuedBy.isNotEmpty()) Detail(stringResource(R.string.issued_by), info.issuedBy)
                    if (info.validFrom.isNotEmpty()) Detail(stringResource(R.string.valid_from), info.validFrom)
                    if (info.validTo.isNotEmpty()) Detail(stringResource(R.string.expires), info.validTo)
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

@Composable
private fun Detail(label: String, value: String) {
    Row {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.6f))
    }
}

/** Full-page warning shown instead of a site whose certificate failed verification. */
@Composable
fun CertificateWarning(problem: SslProblem, onBack: () -> Unit, onProceed: () -> Unit) {
    var advanced by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), strong = true) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f), modifier = Modifier.size(72.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
                    }
                }
                Text(stringResource(R.string.your_connection_isn_t_private), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.ziri_blocked_because_it_can_t_prove_it_i, problem.host),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(problem.reason, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Button(onBack, Modifier.fillMaxWidth()) { Text(stringResource(R.string.back_to_safety)) }
                TextButton({ advanced = !advanced }) { Text(if (advanced) stringResource(R.string.hide_advanced) else stringResource(R.string.advanced)) }
                if (advanced) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        problem.details.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    TextButton(onProceed, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.proceed_to_unsafe, problem.host))
                    }
                }
            }
        }
    }
}

/** Shown instead of a page that only offers plain http (HTTPS-only mode). */
@Composable
fun InsecureWarning(host: String, onBack: () -> Unit, onProceed: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), strong = true) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f), modifier = Modifier.size(72.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.LockOpen, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
                    }
                }
                Text(stringResource(R.string.this_site_isn_t_secure), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(
                    "$host doesn't offer HTTPS, so anything you send or read on it could be seen or changed by others on the network, including passwords.",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onBack, Modifier.fillMaxWidth()) { Text(stringResource(R.string.back_to_safety)) }
                TextButton(onProceed, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.continue_to_anyway, host)) }
            }
        }
    }
}
