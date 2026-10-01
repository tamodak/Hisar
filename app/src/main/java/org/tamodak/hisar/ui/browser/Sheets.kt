package org.tamodak.hisar.ui.browser

import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.tamodak.hisar.R
import org.tamodak.hisar.ui.components.hostOf
import org.tamodak.hisar.web.LinkTarget

/** Long-press menu for a link or image. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkMenuSheet(
    target: LinkTarget,
    onDismiss: () -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onOpenInGroup: (String) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onDownload: (String) -> Unit,
    onSearchImage: (String) -> Unit,
) {
    fun act(action: () -> Unit) {
        onDismiss()
        action()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                target.link ?: target.image.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            target.link?.let { link ->
                SheetItem(Icons.AutoMirrored.Outlined.OpenInNew, R.string.open_in_new_tab) { act { onOpenInNewTab(link) } }
                SheetItem(Icons.Outlined.Tab, R.string.open_in_group) { act { onOpenInGroup(link) } }
                SheetItem(Icons.Outlined.ContentCopy, R.string.copy_link) { act { onCopy(link) } }
                SheetItem(Icons.Outlined.Share, R.string.share_link) { act { onShare(link) } }
                SheetItem(Icons.Outlined.Download, R.string.download_link) { act { onDownload(link) } }
            }
            target.image?.let { image ->
                if (target.link != null) HorizontalDivider()
                SheetItem(Icons.Outlined.ImageSearch, R.string.search_image) { act { onSearchImage(image) } }
                SheetItem(Icons.Outlined.Image, R.string.open_image_new_tab) { act { onOpenInNewTab(image) } }
                SheetItem(Icons.Outlined.Download, R.string.download_image) { act { onDownload(image) } }
                SheetItem(Icons.Outlined.ContentCopy, R.string.copy_image) { act { onCopy(image) } }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SheetItem(icon: ImageVector, label: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

/** Chrome's page info, extended with what Hisar did on this page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageInfoSheet(tab: TabState, onDismiss: () -> Unit) {
    val secure = tab.url.startsWith("https://")
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                hostOf(tab.url),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            InfoRow(
                if (secure) Icons.Outlined.Lock else Icons.Outlined.Warning,
                stringResource(if (secure) R.string.connection_secure else R.string.connection_insecure),
                stringResource(if (secure) R.string.connection_secure_summary else R.string.connection_insecure_summary),
                tint = if (secure) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            InfoRow(
                Icons.Outlined.VerifiedUser,
                stringResource(R.string.protected_by_hisar),
                pluralStringResource(R.plurals.blocked_on_page, tab.blockedOnPage, tab.blockedOnPage),
                tint = MaterialTheme.colorScheme.primary,
            )
            InfoRow(Icons.AutoMirrored.Outlined.ManageSearch, stringResource(R.string.safesearch_enforced), stringResource(R.string.safesearch_enforced_summary))
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, summary: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(icon, null, tint = tint) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/** Chrome's image search entry: take a photo or pick one, then search with it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageSearchSheet(onDismiss: () -> Unit, onTakePhoto: () -> Unit, onPickPhoto: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(stringResource(R.string.image_search_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Text(
                stringResource(R.string.image_search_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            SheetItem(Icons.Outlined.CameraAlt, R.string.take_photo, onTakePhoto)
            SheetItem(Icons.Outlined.PhotoLibrary, R.string.choose_image, onPickPhoto)
        }
    }
}

/** Chrome's "Delete browsing data" with its three common choices. */
@Composable
fun ClearDataDialog(onDismiss: () -> Unit, onConfirm: (history: Boolean, cookies: Boolean, cache: Boolean) -> Unit) {
    var history by rememberSaveable { mutableStateOf(true) }
    var cookies by rememberSaveable { mutableStateOf(true) }
    var cache by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_browsing_data)) },
        text = {
            Column {
                CheckRow(R.string.clear_history, history) { history = it }
                CheckRow(R.string.clear_cookies, cookies) { cookies = it }
                CheckRow(R.string.clear_cache, cache) { cache = it }
            }
        },
        confirmButton = {
            TextButton(enabled = history || cookies || cache, onClick = { onConfirm(history, cookies, cache) }) { Text(stringResource(R.string.delete_data)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CheckRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(stringResource(label), modifier = Modifier.padding(start = 16.dp))
    }
}

/** Replaces the toolbar while searching the page, as Chrome's find bar does. */
@Composable
fun FindInPageBar(webView: WebView, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var active by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    DisposableEffect(webView) {
        webView.setFindListener { ordinal, count, _ ->
            active = if (count == 0) 0 else ordinal + 1
            total = count
        }
        onDispose {
            webView.clearMatches()
            webView.setFindListener(null)
        }
    }
    LaunchedEffect(query) {
        if (query.isEmpty()) {
            webView.clearMatches()
            active = 0
            total = 0
        } else {
            webView.findAllAsync(query)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { webView.findNext(true) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.find_in_page), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    }
                    inner()
                },
            )
            if (query.isNotEmpty()) {
                Text("$active/$total", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
            IconButton(onClick = { webView.findNext(false) }, enabled = total > 0) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.previous_match)) }
            IconButton(onClick = { webView.findNext(true) }, enabled = total > 0) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.next_match)) }
        }
        HorizontalDivider()
    }
}
