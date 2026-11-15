package com.ziribrowser.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziribrowser.AppContainer
import com.ziribrowser.R
import com.ziribrowser.browser.TabState
import com.ziribrowser.passwords.Authenticator
import com.ziribrowser.passwords.PasswordPolicy
import com.ziribrowser.ui.GlassSurface

/** The two small panels above the bottom bar: "Sign in as …" when a saved login fits the page, and "Save password?" after a sign-in. */
@Composable
fun PasswordPrompts(container: AppContainer, tab: TabState, modifier: Modifier = Modifier) {
    val bridge = container.passwordBridge
    val offers by bridge.offers.collectAsStateWithLifecycle()
    val prompts by bridge.prompts.collectAsStateWithLifecycle()
    val needUnlock by container.settings.passwordsUnlock.collectAsStateWithLifecycle(true)
    val context = LocalContext.current
    val prompt = prompts[tab.id]
    val offer = offers[tab.id]
    if (prompt == null && offer == null) return
    val unlockTitle = stringResource(R.string.pw_unlock_prompt)

    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (prompt != null) {
            GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), strong = true) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    val host = PasswordPolicy.displayHost(prompt.origin)
                    Text(
                        stringResource(if (prompt.updateId != null) R.string.pw_update_q else R.string.pw_save_q, host),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    if (prompt.username.isNotEmpty()) Text(prompt.username, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ bridge.never(prompt) }) { Text(stringResource(R.string.pw_never)) }
                        TextButton({ bridge.dismissPrompt(tab.id) }) { Text(stringResource(R.string.pw_not_now)) }
                        Button({ bridge.save(prompt) }) { Text(stringResource(if (prompt.updateId != null) R.string.pw_update else R.string.save)) }
                    }
                }
            }
        }
        if (offer != null && prompt == null) {
            offer.options.take(3).forEach { o ->
                GlassSurface(
                    Modifier, shape = CircleShape, strong = true,
                    onClick = {
                        Authenticator.require(context, needUnlock, unlockTitle) { ok ->
                            if (ok) { container.tabs.fillLogin(tab.id, o.id); bridge.dismissOffer(tab.id) }
                        }
                    },
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            if (o.username.isNotEmpty()) stringResource(R.string.pw_sign_in_as, o.username) else stringResource(R.string.pw_fill_generic),
                            Modifier.padding(start = 10.dp, end = 6.dp), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        )
                        IconButton({ bridge.dismissOffer(tab.id) }) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) }
                    }
                }
            }
        }
    }
}
