package com.ziribrowser.ui.history

import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import com.ziribrowser.ui.LocalBarClearance
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziribrowser.AppContainer
import com.ziribrowser.browser.UrlUtils
import com.ziribrowser.ui.EmptyState
import com.ziribrowser.ui.ZiriHeader
import com.ziribrowser.ui.ZiriSearchField
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val HOUR = 3_600_000L

@Composable
fun HistoryScreen(container: AppContainer, onBack: () -> Unit, onOpenBrowser: () -> Unit) {
    val dao = container.db.history()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val items by remember(query) { dao.search(UrlUtils.likeEscape(query)) }.collectAsStateWithLifecycle(emptyList())
    var clearing by remember { mutableStateOf(false) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ZiriHeader(stringResource(R.string.history), onBack) { TextButton({ clearing = true }) { Text(stringResource(R.string.clear)) } }
        ZiriSearchField(query, { query = it }, stringResource(R.string.search_history))
        if (items.isEmpty()) EmptyState(stringResource(R.string.no_history), Icons.Rounded.History)
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = LocalBarClearance.current)) {
            items(items, key = { it.id }) { h ->
                Row(
                    Modifier.fillMaxWidth().animateItem().clickable(role = androidx.compose.ui.semantics.Role.Button) { container.tabs.openTab(h.url); onOpenBrowser() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(h.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${fmt.format(Date(h.visitedAt))} · ${h.url}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton({ scope.launch { dao.delete(h.id) } }) { Icon(Icons.Rounded.Close, stringResource(R.string.delete)) }
                }
            }
        }
    }

    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(stringResource(R.string.clear_history)) },
            text = { Text(stringResource(R.string.which_period_do_you_want_to_clear)) },
            confirmButton = {
                Column {
                    TextButton({ clearing = false; scope.launch { dao.clearSince(System.currentTimeMillis() - HOUR) } }) { Text(stringResource(R.string.last_hour)) }
                    TextButton({ clearing = false; scope.launch { dao.clearSince(System.currentTimeMillis() - 24 * HOUR) } }) { Text(stringResource(R.string.last_24_hours)) }
                    TextButton({ clearing = false; scope.launch { dao.clearAll() } }) { Text(stringResource(R.string.all_time)) }
                }
            },
            dismissButton = { TextButton({ clearing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
