package org.tamodak.hisar.ui.downloads

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.tamodak.hisar.R
import org.tamodak.hisar.data.model.DownloadItem
import org.tamodak.hisar.data.model.DownloadStatus
import org.tamodak.hisar.data.model.OfflinePage
import org.tamodak.hisar.data.repository.DownloadRepository
import org.tamodak.hisar.data.repository.OfflinePageRepository
import org.tamodak.hisar.ui.components.EmptyState
import org.tamodak.hisar.ui.components.hostOf
import javax.inject.Inject

data class DownloadsUiState(
    val loaded: Boolean = false,
    val pages: List<OfflinePage> = emptyList(),
    val files: List<Pair<DownloadItem, DownloadStatus?>> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloads: DownloadRepository,
    private val offlinePages: OfflinePageRepository,
) : ViewModel() {

    /** Progress is polled from DownloadManager only while the screen is visible ([SharingStarted.WhileSubscribed]). */
    private val statuses = downloads.downloads.flatMapLatest { items ->
        if (items.isEmpty()) flowOf(emptyMap()) else downloads.observeStatuses(items.map { it.id })
    }

    val uiState: StateFlow<DownloadsUiState> = combine(offlinePages.pages, downloads.downloads, statuses) { pages, items, status ->
        DownloadsUiState(loaded = true, pages = pages, files = items.map { it to status[it.id] })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun delete(item: DownloadItem) {
        viewModelScope.launch { downloads.delete(item.id) }
    }

    fun delete(page: OfflinePage) {
        viewModelScope.launch { offlinePages.delete(page) }
    }
}

@Composable
fun DownloadsRoute(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
    onOpenFile: (Long) -> Unit,
    onOpenPage: (OfflinePage) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DownloadsScreen(state, onBack, onOpenFile, onOpenPage, viewModel::delete, viewModel::delete)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    onBack: () -> Unit,
    onOpenFile: (Long) -> Unit,
    onOpenPage: (OfflinePage) -> Unit,
    onDeleteFile: (DownloadItem) -> Unit,
    onDeletePage: (OfflinePage) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.downloads)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (state.loaded && state.pages.isEmpty() && state.files.isEmpty()) {
                item { EmptyState(Icons.Outlined.Download, stringResource(R.string.downloads_empty_title), stringResource(R.string.downloads_empty_body)) }
            }
            if (state.pages.isNotEmpty()) {
                item { SectionTitle(R.string.saved_pages) }
                items(state.pages, key = { "p${it.id}" }) { page -> OfflinePageRow(page, onOpenPage, onDeletePage) }
            }
            if (state.files.isNotEmpty() && state.pages.isNotEmpty()) item { SectionTitle(R.string.files) }
            items(state.files, key = { it.first.id }) { (item, status) -> DownloadRow(item, status, onOpenFile, onDeleteFile) }
        }
    }
}

@Composable
private fun SectionTitle(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

/** A page saved for offline reading: opens in a new tab, from the archive. */
@Composable
private fun OfflinePageRow(page: OfflinePage, onOpen: (OfflinePage) -> Unit, onDelete: (OfflinePage) -> Unit) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(page.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${Formatter.formatShortFileSize(context, page.size)} · ${hostOf(page.url)}", maxLines = 1) },
        leadingContent = { Icon(Icons.Outlined.OfflinePin, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.menu)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = {
                        menuOpen = false
                        onDelete(page)
                    })
                }
            }
        },
        modifier = Modifier.clickable { onOpen(page) },
    )
}

@Composable
private fun DownloadRow(item: DownloadItem, status: DownloadStatus?, onOpen: (Long) -> Unit, onDelete: (DownloadItem) -> Unit) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val done = status?.state == DownloadStatus.State.DONE
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    ListItem(
        headlineContent = { Text(item.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                val text = when (status?.state) {
                    null -> ""
                    DownloadStatus.State.DONE -> "${size(status.total.coerceAtLeast(status.downloaded))} · ${hostOf(item.url)}"
                    DownloadStatus.State.FAILED -> stringResource(R.string.download_state_failed)
                    DownloadStatus.State.MISSING -> stringResource(R.string.download_state_missing)
                    DownloadStatus.State.PAUSED -> stringResource(R.string.download_state_paused)
                    DownloadStatus.State.PENDING -> stringResource(R.string.download_state_pending)
                    DownloadStatus.State.RUNNING ->
                        if (status.total > 0) stringResource(R.string.download_state_progress, size(status.downloaded), size(status.total)) else size(status.downloaded)
                }
                Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (status?.state == DownloadStatus.State.RUNNING || status?.state == DownloadStatus.State.PENDING) {
                    val fraction = status.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
            }
        },
        leadingContent = { Icon(iconFor(item), null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.menu)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (done) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.open)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) }, onClick = {
                            menuOpen = false
                            onOpen(item.id)
                        })
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(if (done) R.string.delete else R.string.cancel_download)) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = {
                            menuOpen = false
                            onDelete(item)
                        },
                    )
                }
            }
        },
        modifier = Modifier.clickable(enabled = done) { onOpen(item.id) },
    )
}

private fun iconFor(item: DownloadItem): ImageVector {
    val mime = item.mimeType.orEmpty()
    val name = item.fileName.lowercase()
    return when {
        mime.startsWith("image/") -> Icons.Outlined.Image
        mime.startsWith("video/") -> Icons.Outlined.VideoFile
        mime.startsWith("audio/") -> Icons.Outlined.AudioFile
        mime == "application/pdf" || name.endsWith(".pdf") -> Icons.Outlined.PictureAsPdf
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
}
