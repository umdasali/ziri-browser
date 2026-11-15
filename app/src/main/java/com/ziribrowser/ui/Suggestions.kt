package com.ziribrowser.ui

import com.ziribrowser.R
import com.ziribrowser.ziriString
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ziribrowser.AppContainer
import com.ziribrowser.browser.UrlUtils
import com.ziribrowser.data.SearchEngine
import kotlinx.coroutines.delay

enum class SuggestionKind(val icon: ImageVector) {
    SEARCH(Icons.Rounded.Search), GO(Icons.Rounded.Language), BOOKMARK(Icons.Rounded.Bookmark), HISTORY(Icons.Rounded.History)
}

data class Suggestion(val kind: SuggestionKind, val title: String, val subtitle: String, val url: String)

/**
 * Suggestions for what the user is typing: the search/go action first, then matching bookmarks and
 * history. Everything is looked up on the device; nothing typed is sent anywhere until the user submits.
 */
@Composable
fun rememberSuggestions(container: AppContainer, query: String, engine: SearchEngine): State<List<Suggestion>> =
    produceState(emptyList(), query, engine) {
        val q = query.trim()
        if (q.isEmpty()) { value = emptyList(); return@produceState }
        delay(120) // wait for a pause in typing; a new keystroke cancels this and restarts it
        val resolved = UrlUtils.resolve(q, engine)
        val isSearch = resolved.startsWith(engine.queryUrl)
        val like = UrlUtils.likeEscape(q)
        val bookmarks = container.db.bookmarks().suggest(like)
        val history = container.db.history().suggest(like)

        val out = ArrayList<Suggestion>()
        out += if (isSearch) Suggestion(SuggestionKind.SEARCH, q, ziriString(R.string.search_2, engine.label), resolved)
        else Suggestion(SuggestionKind.GO, UrlUtils.host(resolved), ziriString(R.string.open_this_address), resolved)
        val seen = HashSet<String>().apply { add(resolved) }
        bookmarks.forEach { if (seen.add(it.url)) out += Suggestion(SuggestionKind.BOOKMARK, it.title.ifEmpty { it.url }, it.url, it.url) }
        history.forEach { if (seen.add(it.url)) out += Suggestion(SuggestionKind.HISTORY, it.title.ifEmpty { it.url }, it.url, it.url) }
        value = out
    }

@Composable
fun SuggestionsPanel(items: List<Suggestion>, onPick: (Suggestion) -> Unit, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    GlassSurface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), strong = true) {
        Column {
            items.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button) { onPick(s) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(s.kind.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            s.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
