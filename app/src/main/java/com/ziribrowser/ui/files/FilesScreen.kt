package com.ziribrowser.ui.files

import com.ziribrowser.ziriString
import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziribrowser.AppContainer
import com.ziribrowser.download.FileUtils
import com.ziribrowser.ui.EmptyState
import com.ziribrowser.ui.GlassSurface
import com.ziribrowser.ui.IconBadge
import com.ziribrowser.ui.LocalBarClearance
import com.ziribrowser.ui.ZiriHeader
import com.ziribrowser.ui.ZiriSearchField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** One file or folder row. [docId] is set for entries inside a folder the user picked. */
private data class FileItem(
    val name: String, val uri: Uri, val mime: String, val size: Long, val modified: Long,
    val isDir: Boolean = false, val docId: String? = null,
) {
    val isVideo get() = mime.startsWith("video/")
    val isAudio get() = mime.startsWith("audio/")
    val isImage get() = mime.startsWith("image/")
}

private sealed interface Where {
    data object Root : Where
    data class Media(val kind: Kind) : Where
    data object ZiriDownloads : Where
    /** A user-picked folder tree; [stack] holds the document ids from the root to the current folder. */
    data class Folder(val tree: Uri, val stack: List<Pair<String, String>>) : Where
}

private enum class Kind(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val collection: Uri, val perm33: String) {
    VIDEOS(ziriString(R.string.videos), Icons.Rounded.VideoLibrary, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Manifest.permission.READ_MEDIA_VIDEO),
    IMAGES(ziriString(R.string.images), Icons.Rounded.Image, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Manifest.permission.READ_MEDIA_IMAGES),
    AUDIO(ziriString(R.string.audio), Icons.Rounded.AudioFile, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Manifest.permission.READ_MEDIA_AUDIO),
}

private enum class Sort(val label: String) { NEWEST(ziriString(R.string.newest_first)), NAME(ziriString(R.string.name)), SIZE(ziriString(R.string.largest_first)) }

/**
 * A simple file manager: browse your videos, images and audio, everything Ziri downloaded, or any folder
 * you pick. Videos and audio open in Ziri's player; other files open in another app after a safety check.
 */
@Composable
fun FilesScreen(container: AppContainer, onPlay: (Uri, String) -> Unit, onOpenViewer: (Long) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("files", Context.MODE_PRIVATE) }
    var where by remember { mutableStateOf<Where>(Where.Root) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(Sort.NEWEST) }
    var sortMenu by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var pendingKind by remember { mutableStateOf<Kind?>(null) }
    var pendingOpen by remember { mutableStateOf<FileItem?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var savedTree by remember { mutableStateOf(prefs.getString("tree", null)?.let(Uri::parse)) }

    fun hasPermission(k: Kind): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) k.perm33 else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val k = pendingKind
        if (ok && k != null) where = Where.Media(k) else Toast.makeText(context, ziriString(R.string.allow_access_to_show_your, k?.label?.lowercase().orEmpty()), Toast.LENGTH_SHORT).show()
    }
    fun openKind(k: Kind) {
        if (hasPermission(k)) where = Where.Media(k)
        else { pendingKind = k; askPermission.launch(if (Build.VERSION.SDK_INT >= 33) k.perm33 else Manifest.permission.READ_EXTERNAL_STORAGE) }
    }
    fun openTree(tree: Uri) {
        val root = DocumentsContract.getTreeDocumentId(tree)
        where = Where.Folder(tree, listOf(root to ziriString(R.string.folder)))
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            prefs.edit().putString("tree", tree.toString()).apply()
            savedTree = tree
            openTree(tree)
        }
    }
    // The file Android was asked to delete: once it confirms, the matching Downloads row must go too, or Downloads keeps listing a missing file.
    var pendingDelete by remember { mutableStateOf<android.net.Uri?>(null) }
    val deleteRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val gone = pendingDelete
        pendingDelete = null
        if (gone != null && res.resultCode == android.app.Activity.RESULT_OK) scope.launch { dropDownloadRow(container, gone) }
        reload++
    }

    BackHandler(where != Where.Root) {
        val w = where
        where = if (w is Where.Folder && w.stack.size > 1) w.copy(stack = w.stack.dropLast(1)) else Where.Root
        query = ""
    }

    val items by produceState<List<FileItem>?>(null, where, reload) {
        value = null
        value = withContext(Dispatchers.IO) {
            when (val w = where) {
                Where.Root -> emptyList()
                is Where.Media -> queryMedia(context, w.kind)
                Where.ZiriDownloads -> emptyList() // shown from the database below
                is Where.Folder -> queryFolder(context, w.tree, w.stack.last().first)
            }
        }
    }
    val downloads by container.db.downloads().observeAll().collectAsStateWithLifecycle(emptyList())

    val title = when (val w = where) {
        Where.Root -> stringResource(R.string.files)
        is Where.Media -> w.kind.label
        Where.ZiriDownloads -> stringResource(R.string.ziri_downloads)
        is Where.Folder -> w.stack.last().second
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ZiriHeader(title, onBack = { if (where == Where.Root) onBack() else { val w = where; where = if (w is Where.Folder && w.stack.size > 1) w.copy(stack = w.stack.dropLast(1)) else Where.Root; query = "" } }) {
            if (where != Where.Root) {
                IconButton({ sortMenu = true }) { Icon(Icons.Rounded.Sort, stringResource(R.string.sort)) }
                DropdownMenu(sortMenu, { sortMenu = false }, shape = RoundedCornerShape(16.dp)) {
                    Sort.entries.forEach { s ->
                        DropdownMenuItem(text = { Text(s.label, fontWeight = if (s == sort) FontWeight.Bold else FontWeight.Normal) }, onClick = { sort = s; sortMenu = false })
                    }
                }
            }
        }

        if (where == Where.Root) {
            RootTiles(
                savedTree = savedTree,
                onKind = ::openKind,
                onDownloads = { where = Where.ZiriDownloads },
                onPickFolder = { pickFolder.launch(null) },
                onSaved = { savedTree?.let(::openTree) },
            )
        } else {
            ZiriSearchField(query, { query = it }, stringResource(R.string.search_in, title))
            val list: List<FileItem>? = if (where == Where.ZiriDownloads) {
                downloads.filter { it.status == com.ziribrowser.data.DownloadStatus.COMPLETED }.map {
                    FileItem(it.filename, Uri.parse(it.fileUri), it.mimeType, it.fileSize, it.createdAt)
                }
            } else items
            val shown = remember(list, query, sort) {
                list.orEmpty().filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }.let { l ->
                    when (sort) {
                        Sort.NEWEST -> l.sortedByDescending { it.modified }
                        Sort.NAME -> l.sortedBy { it.name.lowercase() }
                        Sort.SIZE -> l.sortedByDescending { it.size }
                    }.sortedByDescending { it.isDir }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    list == null -> Text(stringResource(R.string.loading), Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    shown.isEmpty() -> EmptyState(if (query.isBlank()) stringResource(R.string.nothing_here_yet) else stringResource(R.string.no_matches), Icons.Rounded.FolderOpen)
                    else -> LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp + LocalBarClearance.current)) {
                        items(shown, key = { it.uri.toString() }) { f ->
                            FileRow(
                                f,
                                onClick = {
                                    val w = where
                                    when {
                                        f.isDir && w is Where.Folder -> where = w.copy(stack = w.stack + (f.docId!! to f.name))
                                        f.isVideo || f.isAudio -> {
                                            val playable = shown.filter { it.isVideo || it.isAudio }
                                            container.pendingQueue = com.ziribrowser.ui.viewer.PlayQueue(playable.map { com.ziribrowser.ui.viewer.PlayItem(it.uri, it.name, "local:${it.uri}", subtitles = list.orEmpty().filter { o -> o.name.substringAfterLast('.', "").lowercase() in setOf("srt", "vtt", "ass", "ssa") && o.name.substringBeforeLast('.').equals(it.name.substringBeforeLast('.'), true) }.map { o -> o.uri }) }, playable.indexOfFirst { it.uri == f.uri }.coerceAtLeast(0))
                                            onPlay(f.uri, f.name)
                                        }
                                        else -> {
                                            val dl = if (w == Where.ZiriDownloads) downloads.firstOrNull { it.fileUri == f.uri.toString() } else null
                                            if (dl != null && com.ziribrowser.ui.viewer.viewerKindOf(dl) != com.ziribrowser.ui.viewer.ViewerKind.OTHER) onOpenViewer(dl.id)
                                            else pendingOpen = f
                                        }
                                    }
                                },
                                onDelete = { pendingDelete = f.uri; requestDelete(context, container, scope, f, where, deleteRequest::launch) { reload++ } },
                                onRename = if (where is Where.Folder) ({ name ->
                                    val ok = runCatching { DocumentsContract.renameDocument(context.contentResolver, f.uri, name) }.getOrNull() != null
                                    Toast.makeText(context, if (ok) ziriString(R.string.renamed) else ziriString(R.string.couldn_t_rename_this_file), Toast.LENGTH_SHORT).show()
                                    reload++
                                }) else null,
                            )
                        }
                    }
                }
            }
        }
    }

    pendingOpen?.let { f ->
        if (FileUtils.isDangerous(f.name, f.mime)) {
            AlertDialog(
                onDismissRequest = { pendingOpen = null }, title = { Text(stringResource(R.string.open_this_file)) },
                text = { Text(stringResource(R.string.could_be_harmful_an_app_or_script_only_o, f.name)) },
                confirmButton = { TextButton({ openExternally(context, f); pendingOpen = null }) { Text(stringResource(R.string.open_anyway)) } },
                dismissButton = { TextButton({ pendingOpen = null }) { Text(stringResource(R.string.cancel)) } },
            )
        } else { openExternally(context, f); pendingOpen = null }
    }
}

@Composable
private fun RootTiles(savedTree: Uri?, onKind: (Kind) -> Unit, onDownloads: () -> Unit, onPickFolder: () -> Unit, onSaved: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Kind.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k -> Tile(k.label, k.icon, Modifier.weight(1f)) { onKind(k) } }
                if (row.size == 1) Tile(stringResource(R.string.ziri_downloads), Icons.Rounded.Download, Modifier.weight(1f), onDownloads)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(stringResource(R.string.browse_a_folder), Icons.Rounded.FolderOpen, Modifier.weight(1f), onPickFolder)
            if (savedTree != null) Tile(stringResource(R.string.last_folder), Icons.Rounded.Folder, Modifier.weight(1f), onSaved)
            else Box(Modifier.weight(1f))
        }
        Text(
            stringResource(R.string.videos_images_and_audio_need_permission),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Tile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    GlassSurface(modifier, shape = RoundedCornerShape(22.dp), onClick = onClick) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(icon)
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FileRow(f: FileItem, onClick: () -> Unit, onDelete: () -> Unit, onRename: ((String) -> Unit)? = null) {
    var renaming by remember { mutableStateOf(false) }
    var newName by remember(f.uri) { mutableStateOf(f.name) }
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val thumb by produceState<Bitmap?>(null, f.uri) {
        if (f.isVideo || f.isImage) value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(f.uri, Size(160, 160), null) }.getOrNull() }
    }
    Row(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            val t = thumb
            if (t != null) {
                Image(t.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (f.isVideo) Icon(Icons.Rounded.PlayCircle, null, tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
            } else Icon(
                when { f.isDir -> Icons.Rounded.Folder; f.isVideo -> Icons.Rounded.VideoLibrary; f.isAudio -> Icons.Rounded.AudioFile; f.isImage -> Icons.Rounded.Image; else -> Icons.Rounded.InsertDriveFile },
                null, tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(f.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(if (f.isDir) stringResource(R.string.folder) else FileUtils.formatBytes(f.size), if (f.modified > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(f.modified)) else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!f.isDir) Box {
            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more)) }
            DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(16.dp)) {
                DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = { menu = false; share(context, f) })
                if (onRename != null) DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; renaming = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; confirm = true })
            }
        }
    }
    if (renaming && onRename != null) AlertDialog(
        onDismissRequest = { renaming = false }, title = { Text(stringResource(R.string.rename_file)) },
        text = { androidx.compose.material3.OutlinedTextField(newName, { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton({ renaming = false; onRename(FileUtils.sanitizeFilename(newName, f.name)) }, enabled = newName.isNotBlank()) { Text(stringResource(R.string.rename)) } },
        dismissButton = { TextButton({ renaming = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.delete_file)) },
        text = { Text(stringResource(R.string.will_be_deleted_from_your_device, f.name)) },
        confirmButton = { Button({ confirm = false; onDelete() }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---- data access ---------------------------------------------------------------------------------

private fun queryMedia(context: Context, kind: Kind): List<FileItem> {
    val out = ArrayList<FileItem>()
    val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
    context.contentResolver.query(kind.collection, proj, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            out += FileItem(c.getString(1).orEmpty(), ContentUris.withAppendedId(kind.collection, id), c.getString(2).orEmpty(), c.getLong(3), c.getLong(4) * 1000)
        }
    }
    return out
}

private fun queryFolder(context: Context, tree: Uri, docId: String): List<FileItem> {
    val out = ArrayList<FileItem>()
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
    val proj = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    runCatching {
        context.contentResolver.query(children, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val mime = c.getString(2).orEmpty()
                out += FileItem(
                    c.getString(1).orEmpty(), DocumentsContract.buildDocumentUriUsingTree(tree, id), mime, c.getLong(3), c.getLong(4),
                    isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR, docId = id,
                )
            }
        }
    }
    return out
}

/** Removes the Downloads entry for a file that was deleted from the Files screen (no-op if it wasn't one of Ziri's downloads). */
private suspend fun dropDownloadRow(container: AppContainer, uri: android.net.Uri) {
    container.db.downloads().findByUri(uri.toString())?.let { container.downloads.remove(it.id) }
}

private fun requestDelete(
    context: Context, container: AppContainer, scope: kotlinx.coroutines.CoroutineScope, f: FileItem, where: Where,
    launch: (IntentSenderRequest) -> Unit, done: () -> Unit,
) {
    try {
        when {
            where == Where.ZiriDownloads -> {
                scope.launch { container.db.downloads().findByUri(f.uri.toString())?.let { container.downloads.remove(it.id) }; done() }
            }
            where is Where.Folder -> { DocumentsContract.deleteDocument(context.contentResolver, f.uri); scope.launch { dropDownloadRow(container, f.uri) }; done() }
            Build.VERSION.SDK_INT >= 30 -> launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(context.contentResolver, listOf(f.uri)).intentSender).build())
            else -> { context.contentResolver.delete(f.uri, null, null); scope.launch { dropDownloadRow(container, f.uri) }; done() }
        }
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.couldn_t_delete_this_file), Toast.LENGTH_SHORT).show()
    }
}

private fun share(context: Context, f: FileItem) {
    val send = Intent(Intent.ACTION_SEND).setType(f.mime.ifEmpty { "*/*" }).putExtra(Intent.EXTRA_STREAM, f.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, ziriString(R.string.share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun openExternally(context: Context, f: FileItem) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, f.mime.ifEmpty { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try { context.startActivity(view) } catch (_: ActivityNotFoundException) { Toast.makeText(context, context.getString(R.string.no_app_can_open_this_file), Toast.LENGTH_SHORT).show() }
}
