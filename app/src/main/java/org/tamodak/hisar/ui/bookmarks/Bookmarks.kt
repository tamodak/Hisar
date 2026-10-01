package org.tamodak.hisar.ui.bookmarks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.tamodak.hisar.R
import org.tamodak.hisar.data.model.Bookmark
import org.tamodak.hisar.data.repository.BookmarkRepository
import org.tamodak.hisar.ui.components.EmptyState
import org.tamodak.hisar.ui.components.SiteIcon
import org.tamodak.hisar.ui.components.hostOf
import javax.inject.Inject

@HiltViewModel
class BookmarksViewModel @Inject constructor(private val bookmarks: BookmarkRepository) : ViewModel() {
    val bookmarkList: StateFlow<List<Bookmark>?> = bookmarks.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(bookmark: Bookmark) {
        viewModelScope.launch { bookmarks.delete(bookmark) }
    }
}

@Composable
fun BookmarksRoute(
    viewModel: BookmarksViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onShare: (String, String) -> Unit,
) {
    val bookmarks by viewModel.bookmarkList.collectAsStateWithLifecycle()
    BookmarksScreen(bookmarks, onBack, onOpen, onOpenInNewTab, onShare, viewModel::delete)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    bookmarks: List<Bookmark>?,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onShare: (String, String) -> Unit,
    onDelete: (Bookmark) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.bookmarks)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (bookmarks?.isEmpty() == true) {
                item { EmptyState(Icons.Outlined.StarBorder, stringResource(R.string.bookmarks_empty_title), stringResource(R.string.bookmarks_empty_body)) }
            }
            items(bookmarks.orEmpty(), key = { it.url }) { bookmark ->
                val host = hostOf(bookmark.url)
                var menuOpen by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(bookmark.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(host, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { SiteIcon(bookmark.url, host, size = 24.dp) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.menu)) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.open_in_new_tab)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) }, onClick = {
                                    menuOpen = false
                                    onOpenInNewTab(bookmark.url)
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = {
                                    menuOpen = false
                                    onShare(bookmark.url, bookmark.title)
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = {
                                    menuOpen = false
                                    onDelete(bookmark)
                                })
                            }
                        }
                    },
                    modifier = Modifier.clickable { onOpen(bookmark.url) },
                )
            }
        }
    }
}
