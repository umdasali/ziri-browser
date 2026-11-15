package com.ziribrowser.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziribrowser.AppContainer
import com.ziribrowser.R
import com.ziribrowser.data.CredentialEntity
import com.ziribrowser.passwords.Authenticator
import com.ziribrowser.passwords.PasswordCsv
import com.ziribrowser.passwords.PasswordPolicy
import com.ziribrowser.ziriString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Passwords: the saved logins, behind the phone's own unlock. */
@Composable
fun PasswordsPage(container: AppContainer, onBack: () -> Unit) {
    val s = container.settings
    val store = container.passwords
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val offer by s.passwordsOffer.collectAsStateWithLifecycle(true)
    val needUnlock by s.passwordsUnlock.collectAsStateWithLifecycle(true)
    val entries by store.all.collectAsStateWithLifecycle(emptyList())
    val never by store.neverSaved.collectAsStateWithLifecycle(emptyList())
    val unlockTitle = stringResource(R.string.pw_unlock_prompt)

    var unlocked by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(0) }
    LaunchedEffect(asked, needUnlock) {
        Authenticator.require(context, needUnlock, unlockTitle) { ok -> unlocked = ok }
    }

    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CredentialEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                    store.import(PasswordCsv.parse(text))
                }.getOrNull()
            }
            val msg = when {
                added == null || added == 0 -> ziriString(R.string.pw_nothing_imported)
                else -> context.resources.getQuantityString(R.plurals.pw_imported, added, added)
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(PasswordCsv.write(store.export()).toByteArray()) } != null
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) ziriString(R.string.pw_exported) else ziriString(R.string.pw_export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    SettingsPageScaffold(stringResource(R.string.pw_title), onBack) {
        if (!unlocked) {
            SettingsCard {
                SettingRow(
                    stringResource(R.string.pw_unlock_required), icon = Icons.Rounded.Lock,
                    subtitle = stringResource(R.string.pw_unlock_button), onClick = { asked++ },
                )
            }
            return@SettingsPageScaffold
        }

        SettingsCard {
            ToggleRow(stringResource(R.string.pw_offer_title), stringResource(R.string.pw_offer_sub), offer, Icons.Rounded.Key) { scope.launch { s.setPasswordsOffer(it) } }
            RowDivider()
            ToggleRow(stringResource(R.string.pw_unlock_title), stringResource(R.string.pw_unlock_sub), needUnlock, Icons.Rounded.Lock) { scope.launch { s.setPasswordsUnlock(it) } }
        }
        if (needUnlock && !Authenticator.available(context)) {
            Text(stringResource(R.string.pw_no_lock), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }

        SectionLabel(stringResource(R.string.pw_saved))
        SettingsCard {
            SettingRow(stringResource(R.string.pw_add), icon = Icons.Rounded.Add, onClick = { adding = true })
            RowDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    query, { query = it }, Modifier.weight(1f).padding(start = 8.dp), singleLine = true,
                    placeholder = { Text(stringResource(R.string.pw_search)) },
                )
            }
            val shown = entries.filter { query.isBlank() || it.origin.contains(query, true) || it.username.contains(query, true) }
            if (shown.isEmpty()) {
                Text(stringResource(R.string.pw_none), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            shown.forEach { e ->
                RowDivider()
                SettingRow(
                    PasswordPolicy.displayHost(e.origin), icon = Icons.Rounded.Key,
                    subtitle = e.username.ifEmpty { "—" }, onClick = { selected = e },
                )
            }
        }

        SectionLabel(stringResource(R.string.pw_move))
        SettingsCard {
            SettingRow(stringResource(R.string.pw_import), icon = Icons.Rounded.FileDownload, subtitle = stringResource(R.string.pw_import_sub),
                onClick = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "*/*")) })
            RowDivider()
            SettingRow(stringResource(R.string.pw_export), icon = Icons.Rounded.FileUpload, subtitle = stringResource(R.string.pw_export_sub),
                onClick = { if (entries.isNotEmpty()) confirmExport = true })
        }

        if (never.isNotEmpty()) {
            SectionLabel(stringResource(R.string.pw_never_title))
            SettingsCard {
                never.forEachIndexed { i, o ->
                    if (i > 0) RowDivider()
                    SettingRow(
                        PasswordPolicy.displayHost(o), icon = Icons.Rounded.VisibilityOff,
                        trailing = { TextButton({ scope.launch { store.allowSaving(o) } }) { Text(stringResource(R.string.remove)) } },
                    )
                }
            }
        }

        if (entries.isNotEmpty()) {
            SettingsCard(Modifier.padding(top = 16.dp)) {
                SettingRow(stringResource(R.string.pw_delete_all), icon = Icons.Rounded.Delete, onClick = { confirmDeleteAll = true })
            }
        }
    }

    selected?.let { e -> EntryDialog(container, e, needUnlock, onDismiss = { selected = null }) }
    if (adding) EditDialog(null, container, onDismiss = { adding = false })
    if (confirmExport) AlertDialog(
        onDismissRequest = { confirmExport = false },
        title = { Text(stringResource(R.string.pw_export)) },
        text = { Text(stringResource(R.string.pw_export_warn)) },
        confirmButton = {
            TextButton({
                confirmExport = false
                Authenticator.require(context, needUnlock, unlockTitle) { ok -> if (ok) exportLauncher.launch("ziri-passwords.csv") }
            }) { Text(stringResource(R.string.pw_export)) }
        },
        dismissButton = { TextButton({ confirmExport = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmDeleteAll) AlertDialog(
        onDismissRequest = { confirmDeleteAll = false },
        title = { Text(stringResource(R.string.pw_delete_all)) },
        text = { Text(stringResource(R.string.pw_delete_all_q, entries.size)) },
        confirmButton = {
            TextButton({ confirmDeleteAll = false; scope.launch { store.deleteAll() } }, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = { TextButton({ confirmDeleteAll = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun EntryDialog(container: AppContainer, e: CredentialEntity, needUnlock: Boolean, onDismiss: () -> Unit) {
    val store = container.passwords
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val unlockTitle = stringResource(R.string.pw_unlock_prompt)
    var shown by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val password = remember(e) { store.reveal(e) }

    if (editing) { EditDialog(e, container, onDismiss = { editing = false; onDismiss() }); return }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(PasswordPolicy.displayHost(e.origin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(stringResource(R.string.pw_site), e.origin)
                Field(stringResource(R.string.pw_username), e.username.ifEmpty { "—" })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.pw_password), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            when { password == null -> stringResource(R.string.pw_cant_read); shown -> password; else -> "••••••••••" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (password != null) IconButton({
                        if (shown) shown = false else Authenticator.require(context, needUnlock, unlockTitle) { ok -> if (ok) shown = true }
                    }) { Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(if (shown) R.string.pw_hide else R.string.pw_show)) }
                }
                Row {
                    TextButton({ copySecret(context, container, "username", e.username) }, enabled = e.username.isNotEmpty()) { Text(stringResource(R.string.pw_copy_user)) }
                    TextButton({
                        if (password != null) Authenticator.require(context, needUnlock, unlockTitle) { ok -> if (ok) copySecret(context, container, "password", password) }
                    }, enabled = password != null) { Text(stringResource(R.string.pw_copy_pass)) }
                }
            }
        },
        confirmButton = { TextButton({ editing = true }) { Text(stringResource(R.string.edit)) } },
        dismissButton = {
            Row {
                TextButton({ confirmDelete = true }, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) }
                TextButton(onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.pw_delete_q)) },
        text = { Text(PasswordPolicy.displayHost(e.origin) + if (e.username.isNotEmpty()) " · ${e.username}" else "") },
        confirmButton = { TextButton({ confirmDelete = false; scope.launch { store.delete(e.id) }; onDismiss() }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Adds a login (when [existing] is null) or edits one. */
@Composable
private fun EditDialog(existing: CredentialEntity?, container: AppContainer, onDismiss: () -> Unit) {
    val store = container.passwords
    val scope = rememberCoroutineScope()
    var site by remember { mutableStateOf(existing?.origin.orEmpty()) }
    var user by remember { mutableStateOf(existing?.username.orEmpty()) }
    var pass by remember { mutableStateOf(existing?.let { store.reveal(it) }.orEmpty()) }
    var show by remember { mutableStateOf(false) }
    val origin = PasswordPolicy.originOf(site.trim().let { if ("://" in it || it.isEmpty()) it else "https://$it" })
    val valid = origin != null && PasswordPolicy.eligible(origin) && pass.isNotEmpty() && pass.length <= PasswordPolicy.MAX_FIELD
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.pw_add else R.string.edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(site, { site = it }, label = { Text(stringResource(R.string.pw_site)) }, singleLine = true, enabled = existing == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.pw_username)) }, singleLine = true)
                OutlinedTextField(
                    pass, { pass = it }, label = { Text(stringResource(R.string.pw_password)) }, singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton({ show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(if (show) R.string.pw_hide else R.string.pw_show)) } },
                )
            }
        },
        confirmButton = {
            Button({
                scope.launch { if (existing != null) store.update(existing.id, user, pass) else store.add(origin!!, user, pass) }
                onDismiss()
            }, enabled = valid) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Copies text marked as sensitive (hidden from clipboard previews) and clears it again after a minute. */
private fun copySecret(context: Context, container: AppContainer, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
    Toast.makeText(context, ziriString(R.string.pw_copied), Toast.LENGTH_SHORT).show()
    container.scope.launch {
        delay(60_000)
        runCatching { if (cm.primaryClip?.getItemAt(0)?.text?.toString() == text) cm.clearPrimaryClip() }
    }
}
