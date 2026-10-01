package org.tamodak.hisar.ui.recenttabs

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import org.tamodak.hisar.data.model.ClosedTab
import org.tamodak.hisar.data.repository.RecentTabsRepository
import org.tamodak.hisar.ui.components.EmptyState
import org.tamodak.hisar.ui.components.SiteIcon
import org.tamodak.hisar.ui.components.hostOf
import javax.inject.Inject

@HiltViewModel
class RecentTabsViewModel @Inject constructor(private val recentTabs: RecentTabsRepository) : ViewModel() {
    val closedTabs: StateFlow<List<ClosedTab>?> = recentTabs.closedTabs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clear() {
        viewModelScope.launch { recentTabs.clear() }
    }
}

@Composable
fun RecentTabsRoute(viewModel: RecentTabsViewModel, onBack: () -> Unit, onReopen: (String) -> Unit) {
    val closed by viewModel.closedTabs.collectAsStateWithLifecycle()
    RecentTabsScreen(closed, onBack, onReopen, viewModel::clear)
}

/** Chrome's "Recent tabs": recently closed tabs, newest first, tap to reopen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentTabsScreen(closed: List<ClosedTab>?, onBack: () -> Unit, onReopen: (String) -> Unit, onClear: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.recent_tabs)) },
                actions = { if (!closed.isNullOrEmpty()) TextButton(onClick = onClear) { Text(stringResource(R.string.clear)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            when {
                closed == null -> Unit
                closed.isEmpty() -> item { EmptyState(Icons.Outlined.Restore, stringResource(R.string.recent_tabs_empty), null) }
                else -> item {
                    Text(
                        stringResource(R.string.recently_closed),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            items(closed.orEmpty(), key = { it.url }) { entry ->
                val host = hostOf(entry.url)
                val ago = DateUtils.getRelativeTimeSpanString(entry.closedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                ListItem(
                    headlineContent = { Text(entry.title.ifBlank { host }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("$host · $ago", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { SiteIcon(entry.url, host, size = 24.dp) },
                    modifier = Modifier.clickable { onReopen(entry.url) },
                )
            }
        }
    }
}
