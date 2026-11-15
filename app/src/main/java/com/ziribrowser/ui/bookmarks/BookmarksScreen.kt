package com.ziribrowser.ui.bookmarks

import com.ziribrowser.ziriString
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
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
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
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalContext
import com.ziribrowser.data.BookmarkEntity
import com.ziribrowser.data.BookmarkIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ziribrowser.ui.EmptyState
import com.ziribrowser.ui.SiteAvatar
import com.ziribrowser.ui.ZiriHeader
import com.ziribrowser.ui.ZiriSearchField
import kotlinx.coroutines.launch

private const val MAX_IMPORT_BYTES = 10 * 1024 * 1024

/** Reads a stream as text, failing if it is bigger than [max] bytes (InputStream.readNBytes needs Android 13). */
private fun readCapped(input: java.io.InputStream, max: Int): String {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        if (out.size() + n > max) throw java.io.IOException(ziriString(R.string.file_too_large))
        out.write(chunk, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}

@Composable
fun BookmarksScreen(container: AppContainer, onOpenBrowser: () -> Unit) {
    val dao = container.db.bookmarks()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val items by remember(query) { dao.search(UrlUtils.likeEscape(query)) }.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<BookmarkEntity?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val html = BookmarkIo.toHtml(dao.all())
                    context.contentResolver.openOutputStream(uri)?.use { it.write(html.toByteArray()) } != null
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) ziriString(R.string.bookmarks_exported) else ziriString(R.string.couldn_t_export_bookmarks), Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { readCapped(it, MAX_IMPORT_BYTES) }.orEmpty()
                    val parsed = BookmarkIo.parseHtml(text)
                    val before = dao.all().size
                    dao.insertAll(parsed)
                    dao.all().size - before
                }.getOrNull()
            }
            Toast.makeText(
                context,
                when {
                    added == null -> ziriString(R.string.couldn_t_read_that_file)
                    added == 0 -> ziriString(R.string.no_new_bookmarks_found)
                    else -> com.ziribrowser.ZiriApp.instance.resources.getQuantityString(R.plurals.imported_bookmarks, added, added)
                },
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ZiriHeader(stringResource(R.string.bookmarks)) {
            Box {
                IconButton({ menuOpen = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem(
                        { Text(stringResource(R.string.import_from_file)) },
                        { menuOpen = false; importLauncher.launch(arrayOf("text/html", "application/xhtml+xml", "text/plain")) },
                        leadingIcon = { Icon(Icons.Rounded.FileDownload, null) },
                    )
                    DropdownMenuItem(
                        { Text(stringResource(R.string.export_to_file)) },
                        { menuOpen = false; exportLauncher.launch("ziri-bookmarks.html") },
                        leadingIcon = { Icon(Icons.Rounded.FileUpload, null) },
                    )
                }
            }
        }
        ZiriSearchField(query, { query = it }, stringResource(R.string.search_bookmarks))
        if (items.isEmpty()) EmptyState(stringResource(R.string.no_bookmarks_yet), Icons.Rounded.Bookmark)
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = LocalBarClearance.current)) {
            items(items, key = { it.id }) { b ->
                Row(
                    Modifier.fillMaxWidth().animateItem().clickable(role = androidx.compose.ui.semantics.Role.Button) {
                        container.tabs.openTab(b.url); onOpenBrowser()
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.ziribrowser.ui.SiteIcon(container, b.url, b.title.ifEmpty { b.url }, 40.dp, Modifier.padding(end = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (b.folder.isNotEmpty()) "${b.folder} · " else "") + b.url, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton({ editing = b }) { Icon(Icons.Rounded.Edit, stringResource(R.string.edit)) }
                    IconButton({ scope.launch { dao.delete(b.id) } }) { Icon(Icons.Rounded.Close, stringResource(R.string.delete)) }
                }
            }
        }
    }

    editing?.let { b ->
        var title by remember(b.id) { mutableStateOf(b.title) }
        var folder by remember(b.id) { mutableStateOf(b.folder) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.edit_bookmark)) },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, singleLine = true, label = { Text(stringResource(R.string.title)) })
                    OutlinedTextField(folder, { folder = it }, singleLine = true, label = { Text(stringResource(R.string.folder_optional)) })
                }
            },
            confirmButton = {
                TextButton({
                    scope.launch { dao.update(b.id, title.trim().ifEmpty { b.url }, folder.trim()) }
                    editing = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
