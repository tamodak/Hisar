package org.tamodak.hisar.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.tamodak.hisar.R
import org.tamodak.hisar.data.model.HistoryEntry
import org.tamodak.hisar.data.repository.HistoryRepository
import org.tamodak.hisar.ui.components.EmptyState
import org.tamodak.hisar.ui.components.SiteIcon
import org.tamodak.hisar.ui.components.hostOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

data class HistoryUiState(val query: String = "", val days: List<Pair<LocalDate, List<HistoryEntry>>> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(private val history: HistoryRepository) : ViewModel() {
    private val query = MutableStateFlow("")

    val uiState: StateFlow<HistoryUiState> = query.flatMapLatest { q ->
        history.observe(q).map { entries ->
            val zone = ZoneId.systemDefault()
            HistoryUiState(q, entries.groupBy { Instant.ofEpochMilli(it.visitedAt).atZone(zone).toLocalDate() }.toList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun onQueryChange(text: String) {
        query.value = text
    }

    fun delete(entry: HistoryEntry) {
        viewModelScope.launch { history.delete(entry) }
    }
}

@Composable
fun HistoryRoute(viewModel: HistoryViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onDeleteBrowsingData: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HistoryScreen(state, onBack, viewModel::onQueryChange, onOpen, viewModel::delete, onDeleteBrowsingData)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onDeleteBrowsingData: () -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = searching) {
        searching = false
        onQueryChange("")
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { if (searching) SearchField(state.query, onQueryChange) else Text(stringResource(R.string.history)) },
                actions = {
                    if (searching) {
                        IconButton(onClick = {
                            searching = false
                            onQueryChange("")
                        }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear)) }
                    } else {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, stringResource(R.string.search)) }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (!searching) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.delete_browsing_data_ellipsis), color = MaterialTheme.colorScheme.primary) },
                        leadingContent = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable(onClick = onDeleteBrowsingData),
                    )
                    HorizontalDivider()
                }
            }
            if (state.days.isEmpty()) item { EmptyState(Icons.Outlined.History, stringResource(R.string.history_empty), null) }
            state.days.forEach { (day, entries) ->
                item(key = day.toString()) {
                    Text(dayLabel(day), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
                }
                items(entries, key = { it.id }) { entry ->
                    val host = hostOf(entry.url)
                    ListItem(
                        headlineContent = { Text(entry.title.ifBlank { host }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(host, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { SiteIcon(entry.url, host, size = 24.dp) },
                        trailingContent = { IconButton(onClick = { onDelete(entry) }) { Icon(Icons.Outlined.Close, stringResource(R.string.remove)) } },
                        modifier = Modifier.clickable { onOpen(entry.url) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search_history)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.focusRequester(focus),
    )
}

@Composable
private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
    }
}
