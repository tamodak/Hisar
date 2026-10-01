package org.tamodak.hisar.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FormatColorText
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.tamodak.hisar.BuildConfig
import org.tamodak.hisar.R
import org.tamodak.hisar.data.model.SimpleFont
import org.tamodak.hisar.data.model.UserSettings
import org.tamodak.hisar.data.repository.FilterRepository
import org.tamodak.hisar.data.repository.SettingsRepository
import org.tamodak.hisar.search.SearchEngine
import org.tamodak.hisar.ui.theme.family
import java.text.NumberFormat
import javax.inject.Inject
import kotlin.math.roundToInt

/** The sizes of the built-in lists, shown under "Hisar protection". */
data class ProtectionStats(val adDomains: Int, val harmfulDomains: Int, val words: Int)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    filters: FilterRepository,
) : ViewModel() {
    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    val protection: StateFlow<ProtectionStats?> = flow {
        val guard = filters.guard()
        emit(ProtectionStats(guard.adDomainCount, guard.harmfulDomainCount, guard.blockedWordCount))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setSearchEngine(engine: SearchEngine) = launch { settingsRepository.setSearchEngine(engine) }
    fun setSearchSuggestions(enabled: Boolean) = launch { settingsRepository.setSearchSuggestions(enabled) }
    fun setTextZoom(percent: Int) = launch { settingsRepository.setTextZoom(percent) }
    fun setSimpleMode(enabled: Boolean) = launch { settingsRepository.setSimpleMode(enabled) }
    fun setSimpleFont(font: SimpleFont) = launch { settingsRepository.setSimpleFont(font) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@Composable
fun SettingsRoute(viewModel: SettingsViewModel, onBack: () -> Unit, onDeleteBrowsingData: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val protection by viewModel.protection.collectAsStateWithLifecycle()
    SettingsScreen(
        settings = settings,
        protection = protection,
        onBack = onBack,
        onSearchEngine = viewModel::setSearchEngine,
        onSearchSuggestions = viewModel::setSearchSuggestions,
        onTextZoom = viewModel::setTextZoom,
        onSimpleMode = viewModel::setSimpleMode,
        onSimpleFont = viewModel::setSimpleFont,
        onDeleteBrowsingData = onDeleteBrowsingData,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: UserSettings,
    protection: ProtectionStats?,
    onBack: () -> Unit,
    onSearchEngine: (SearchEngine) -> Unit,
    onSearchSuggestions: (Boolean) -> Unit,
    onTextZoom: (Int) -> Unit,
    onSimpleMode: (Boolean) -> Unit,
    onSimpleFont: (SimpleFont) -> Unit,
    onDeleteBrowsingData: () -> Unit,
) {
    var engineDialog by rememberSaveable { mutableStateOf(false) }
    val numbers = NumberFormat.getInstance()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.settings)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section(R.string.section_basics)
            ListItem(
                headlineContent = { Text(stringResource(R.string.search_engine)) },
                supportingContent = { Text(settings.searchEngine.label) },
                leadingContent = { Icon(Icons.Outlined.Search, null) },
                modifier = Modifier.clickable { engineDialog = true },
            )

            Section(R.string.section_privacy)
            ListItem(
                headlineContent = { Text(stringResource(R.string.search_suggestions)) },
                supportingContent = { Text(stringResource(R.string.search_suggestions_summary)) },
                leadingContent = { Icon(Icons.Outlined.TipsAndUpdates, null) },
                trailingContent = { Switch(checked = settings.searchSuggestions, onCheckedChange = null) },
                modifier = Modifier.selectable(selected = settings.searchSuggestions, role = Role.Switch) { onSearchSuggestions(!settings.searchSuggestions) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.delete_browsing_data)) },
                supportingContent = { Text(stringResource(R.string.delete_browsing_data_summary)) },
                leadingContent = { Icon(Icons.Outlined.Delete, null) },
                modifier = Modifier.clickable(onClick = onDeleteBrowsingData),
            )

            Section(R.string.section_accessibility)
            // The slider moves freely; the setting is saved once, when it is let go.
            var zoom by remember(settings.textZoom) { mutableIntStateOf(settings.textZoom) }
            ListItem(
                headlineContent = { Text(stringResource(R.string.text_scaling)) },
                leadingContent = { Icon(Icons.Outlined.FormatSize, null) },
                trailingContent = { Text("$zoom%") },
            )
            Slider(
                value = zoom.toFloat(),
                onValueChange = { zoom = (it / 10).roundToInt() * 10 },
                onValueChangeFinished = { onTextZoom(zoom) },
                valueRange = 50f..200f,
                steps = 14,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                stringResource(R.string.text_scaling_preview),
                fontSize = (16 * zoom / 100f).sp,
                lineHeight = (22 * zoom / 100f).sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.simple_mode)) },
                supportingContent = { Text(stringResource(R.string.simple_mode_summary)) },
                leadingContent = { Icon(Icons.Outlined.FormatColorText, null) },
                trailingContent = { Switch(checked = settings.simpleMode, onCheckedChange = null) },
                modifier = Modifier.selectable(selected = settings.simpleMode, role = Role.Switch) { onSimpleMode(!settings.simpleMode) },
            )
            if (settings.simpleMode) {
                Text(stringResource(R.string.simple_mode_font), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 72.dp, bottom = 4.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SimpleFont.entries.forEach { font ->
                        FilterChip(
                            selected = settings.simpleFont == font,
                            onClick = { onSimpleFont(font) },
                            label = { Text(stringResource(fontLabel(font)), fontFamily = font.family) },
                        )
                    }
                }
            }

            Section(R.string.section_protection)
            ProtectionRow(Icons.AutoMirrored.Outlined.ManageSearch, stringResource(R.string.prot_safesearch), stringResource(R.string.prot_safesearch_summary))
            ProtectionRow(Icons.Outlined.Block, stringResource(R.string.prot_ads), protection?.let { stringResource(R.string.domains_count, numbers.format(it.adDomains)) })
            ProtectionRow(Icons.Outlined.Shield, stringResource(R.string.prot_harmful), protection?.let { stringResource(R.string.domains_count, numbers.format(it.harmfulDomains)) })
            ProtectionRow(Icons.Outlined.Spellcheck, stringResource(R.string.prot_words), protection?.let { stringResource(R.string.words_count, numbers.format(it.words)) })
            Text(
                stringResource(R.string.protection_always_on),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Section(R.string.section_about)
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_name)) },
                supportingContent = { Text(stringResource(R.string.version, BuildConfig.VERSION_NAME)) },
                leadingContent = { Icon(Icons.Outlined.Info, null) },
            )
        }
    }

    if (engineDialog) {
        AlertDialog(
            onDismissRequest = { engineDialog = false },
            title = { Text(stringResource(R.string.search_engine)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    SearchEngine.entries.forEach { engine ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = engine == settings.searchEngine, role = Role.RadioButton) {
                                    onSearchEngine(engine)
                                    engineDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = engine == settings.searchEngine, onClick = null)
                            Text(engine.label, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { engineDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun ProtectionRow(icon: ImageVector, title: String, summary: String?) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Icon(Icons.Outlined.CheckCircle, stringResource(R.string.always_on), tint = MaterialTheme.colorScheme.primary) },
    )
}

private fun fontLabel(font: SimpleFont) = when (font) {
    SimpleFont.SANS -> R.string.font_sans
    SimpleFont.SERIF -> R.string.font_serif
    SimpleFont.MONO -> R.string.font_mono
    SimpleFont.CONDENSED -> R.string.font_condensed
}
