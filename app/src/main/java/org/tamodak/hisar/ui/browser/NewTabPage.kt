package org.tamodak.hisar.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NorthWest
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.tamodak.hisar.R
import org.tamodak.hisar.data.model.Shortcut
import org.tamodak.hisar.data.model.OfflinePage
import org.tamodak.hisar.ui.components.SiteIcon
import org.tamodak.hisar.ui.components.hostOf

/**
 * Chrome's new tab page without the Discover feed: the wordmark, the search box with voice and
 * image search, and the most-visited tiles.
 */
@Composable
fun NewTabPage(
    tiles: List<Shortcut>,
    onSearchBoxVisibleChange: (Boolean) -> Unit,
    onSearchBoxClick: () -> Unit,
    onVoiceSearch: () -> Unit,
    onImageSearch: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onCopyLink: (String) -> Unit,
    onRemoveTile: (Shortcut) -> Unit,
    onEditTile: (original: Shortcut, title: String, url: String) -> Boolean,
) {
    var editing by remember { mutableStateOf<Shortcut?>(null) }
    val scroll = rememberScrollState()
    // The toolbar's address bar stays hidden while this page's own search box is on screen, and
    // appears once it scrolls away, as in Chrome.
    var boxBottom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val currentOnChange by rememberUpdatedState(onSearchBoxVisibleChange)
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value < boxBottom }.distinctUntilChanged().collect { currentOnChange(it) }
    }
    DisposableEffect(Unit) { onDispose { onSearchBoxVisibleChange(false) } }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(scroll).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_hisar_logo), null, Modifier.size(width = 48.dp, height = 58.dp), tint = Color.Unspecified)
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(36.dp))
        // Chrome's fake search box, with a search icon before the text, and voice and image
        // search on the right.
        Row(
            Modifier
                .onGloballyPositioned { boxBottom = (it.positionInParent().y + it.size.height).toInt() }
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onSearchBoxClick)
                .padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.address_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onVoiceSearch) {
                Icon(Icons.Outlined.Mic, stringResource(R.string.voice_search), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onImageSearch) {
                Icon(Icons.Outlined.CameraAlt, stringResource(R.string.image_search), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { shortcut ->
                        Tile(
                            shortcut,
                            onOpen = onOpen,
                            onOpenInNewTab = onOpenInNewTab,
                            onEdit = { editing = shortcut },
                            onCopyLink = onCopyLink,
                            onRemove = onRemoveTile,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
    editing?.let { original ->
        EditShortcutDialog(
            original = original,
            onDismiss = { editing = null },
            onSave = { title, url -> onEditTile(original, title, url).also { saved -> if (saved) editing = null } },
        )
    }
}

/**
 * A shortcut tile. A long press opens Chrome's menu for it: open in a new tab, edit (which pins
 * it), copy the link, or delete it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    shortcut: Shortcut,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onEdit: () -> Unit,
    onCopyLink: (String) -> Unit,
    onRemove: (Shortcut) -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = hostOf(shortcut.url)
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = { onOpen(shortcut.url) }, onLongClick = { menuOpen = true })
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                // Tiles may show sites not visited yet, so their icons may be fetched.
                SiteIcon(shortcut.url, shortcut.title.ifBlank { host }, size = 28.dp, fetch = true)
            }
            Text(
                shortcut.title.ifBlank { host },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            TileMenuItem(Icons.AutoMirrored.Outlined.OpenInNew, R.string.open_in_new_tab) {
                menuOpen = false
                onOpenInNewTab(shortcut.url)
            }
            TileMenuItem(Icons.Outlined.Edit, R.string.edit) {
                menuOpen = false
                onEdit()
            }
            TileMenuItem(Icons.Outlined.ContentCopy, R.string.copy_link) {
                menuOpen = false
                onCopyLink(shortcut.url)
            }
            TileMenuItem(Icons.Outlined.Delete, R.string.delete) {
                menuOpen = false
                onRemove(shortcut)
            }
        }
    }
}

@Composable
private fun TileMenuItem(icon: ImageVector, label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

/** Chrome's "Edit shortcut": a name and an address. Saving pins the tile. */
@Composable
private fun EditShortcutDialog(original: Shortcut, onDismiss: () -> Unit, onSave: (title: String, url: String) -> Boolean) {
    var title by rememberSaveable { mutableStateOf(original.title) }
    var url by rememberSaveable { mutableStateOf(original.url) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_shortcut)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.shortcut_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.shortcut_url)) },
                    singleLine = true,
                    isError = invalid,
                    supportingText = if (invalid) ({ Text(stringResource(R.string.shortcut_url_invalid)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && url.isNotBlank(), onClick = { invalid = !onSave(title, url) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Rows under the address bar while it is being edited. */
@Composable
fun SuggestionList(
    tab: TabState?,
    offlinePage: OfflinePage?,
    input: String,
    suggestions: List<Suggestion>,
    onNavigate: (String) -> Unit,
    onRefine: (String) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String, String?) -> Unit,
) {
    val pageUrl = offlinePage?.url ?: tab?.url?.takeUnless { tab.showNewTabPage }.orEmpty()
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Unedited address: Chrome offers the current page with copy and share.
        if (pageUrl.isNotEmpty() && (input.isEmpty() || input == pageUrl)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SiteIcon(pageUrl, hostOf(pageUrl), size = 24.dp, icon = tab?.favicon)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(tab?.displayTitle.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text(pageUrl, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onCopy(pageUrl) }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy_link)) }
                    IconButton(onClick = { onShare(pageUrl, tab?.title) }) { Icon(Icons.Outlined.Share, stringResource(R.string.share)) }
                }
                HorizontalDivider()
            }
        }
        if (input != pageUrl) {
            items(suggestions) { s -> SuggestionRow(s, onNavigate, onRefine) }
        }
    }
}

@Composable
private fun SuggestionRow(s: Suggestion, onNavigate: (String) -> Unit, onRefine: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onNavigate(s.url) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (s.kind) {
                Suggestion.Kind.SEARCH -> Icons.Outlined.Search
                Suggestion.Kind.URL -> Icons.Outlined.Public
                Suggestion.Kind.HISTORY -> Icons.Outlined.History
                Suggestion.Kind.BOOKMARK -> Icons.Outlined.StarBorder
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(s.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            s.subtitle?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (s.kind == Suggestion.Kind.SEARCH) {
            IconButton(onClick = { onRefine(s.text) }) {
                Icon(Icons.Outlined.NorthWest, stringResource(R.string.refine), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
