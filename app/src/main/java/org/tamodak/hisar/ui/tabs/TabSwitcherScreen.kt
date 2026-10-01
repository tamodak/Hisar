package org.tamodak.hisar.ui.tabs

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.tamodak.hisar.R
import org.tamodak.hisar.data.model.TabGroup
import org.tamodak.hisar.ui.browser.BrowserUiState
import org.tamodak.hisar.ui.browser.TabState
import org.tamodak.hisar.ui.components.SiteIcon

/** What the tab grid and group sheet can do; implemented with the browser's ViewModel and controller. */
interface TabActions {
    fun select(tabId: Long)
    fun close(tabId: Long)
    fun newTab()
    fun closeAll()
    fun createGroup(tabId: Long)
    fun addToGroup(tabId: Long, groupId: Long)
    fun removeFromGroup(tabId: Long)
    fun openGroup(groupId: Long)
    fun closeGroup(groupId: Long)
    fun ungroup(groupId: Long)
    fun renameGroup(groupId: Long, name: String)
    fun recolorGroup(groupId: Long, colorIndex: Int)
    fun newTabInGroup(groupId: Long)
}

/** One cell of the tab grid: a lone tab, or a whole group shown as one card. */
private sealed interface GridItem {
    val key: String
    data class Single(val tab: TabState) : GridItem {
        override val key get() = "t${tab.id}"
    }
    data class Group(val group: TabGroup) : GridItem {
        override val key get() = "g${group.id}"
    }
}

/** Chrome's tab grid: tab cards, group cards, and a New tab button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabSwitcherScreen(
    state: BrowserUiState,
    thumbnails: Map<Long, Bitmap>,
    actions: TabActions,
    onBack: () -> Unit,
    onOpenRecentTabs: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var menuOpen by remember { mutableStateOf(false) }
    val items = buildList {
        val seen = HashSet<Long>()
        for (tab in state.tabs) {
            val group = state.groupOf(tab)
            when {
                group == null -> add(GridItem.Single(tab))
                seen.add(group.id) -> add(GridItem.Group(group))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    FilledTonalButton(onClick = actions::newTab, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.new_tab))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.menu)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.close_all_tabs)) }, onClick = {
                                menuOpen = false
                                actions.closeAll()
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.recent_tabs)) }, onClick = {
                                menuOpen = false
                                onOpenRecentTabs()
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, onClick = {
                                menuOpen = false
                                onOpenSettings()
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + 12.dp,
                end = padding.calculateEndPadding(direction) + 12.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.key }) { item ->
                when (item) {
                    is GridItem.Single -> TabCard(item.tab, thumbnails[item.tab.id], item.tab.id == state.currentTabId, state.groups, actions)
                    is GridItem.Group -> GroupCard(item.group, state.tabsIn(item.group), thumbnails, state.currentTab?.groupId == item.group.id, actions)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabCard(tab: TabState, thumbnail: Bitmap?, selected: Boolean, groups: List<TabGroup>, actions: TabActions) {
    val title = if (tab.showNewTabPage) stringResource(R.string.new_tab) else tab.displayTitle
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Card(
            shape = RoundedCornerShape(16.dp),
            border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = { actions.select(tab.id) }, onLongClick = { menuOpen = true }),
        ) {
            Column {
                Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (tab.showNewTabPage) {
                        Icon(Icons.Filled.Shield, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    } else {
                        SiteIcon(tab.url, title, size = 16.dp, icon = tab.favicon)
                    }
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    IconButton(onClick = { actions.close(tab.id) }) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.close_tab), Modifier.size(18.dp))
                    }
                }
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.78f).padding(start = 4.dp, end = 4.dp, bottom = 4.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    TabPreview(tab, thumbnail, title, 40)
                }
            }
        }
        // Long-press: Chrome's grouping actions.
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (tab.groupId == null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.add_to_new_group)) }, onClick = {
                    menuOpen = false
                    actions.createGroup(tab.id)
                })
                groups.forEach { g ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_to_group, g.name.ifBlank { stringResource(R.string.unnamed_group) })) }, onClick = {
                        menuOpen = false
                        actions.addToGroup(tab.id, g.id)
                    })
                }
            } else {
                DropdownMenuItem(text = { Text(stringResource(R.string.remove_from_group)) }, onClick = {
                    menuOpen = false
                    actions.removeFromGroup(tab.id)
                })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.close_tab)) }, onClick = {
                menuOpen = false
                actions.close(tab.id)
            })
        }
    }
}

@Composable
private fun TabPreview(tab: TabState, thumbnail: Bitmap?, title: String, iconSize: Int) {
    when {
        tab.showNewTabPage -> Icon(Icons.Filled.Shield, null, Modifier.size(iconSize.dp), tint = MaterialTheme.colorScheme.primary)
        thumbnail != null -> Image(thumbnail.asImageBitmap(), null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        else -> SiteIcon(tab.url, title, size = iconSize.dp, icon = tab.favicon)
    }
}

/** A group in the grid: its colour, name and close button, with its first four tabs in a 2×2 preview. */
@Composable
private fun GroupCard(group: TabGroup, members: List<TabState>, thumbnails: Map<Long, Bitmap>, selected: Boolean, actions: TabActions) {
    Card(
        onClick = { actions.openGroup(group.id) },
        shape = RoundedCornerShape(16.dp),
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(TabGroup.COLORS[group.colorIndex])))
                Text(groupLabel(group, members.size), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                IconButton(onClick = { actions.closeGroup(group.id) }) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.close_group), Modifier.size(18.dp))
                }
            }
            Column(Modifier.fillMaxWidth().aspectRatio(0.78f).padding(start = 4.dp, end = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                members.take(4).chunked(2).forEach { row ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { t ->
                            Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                                TabPreview(t, thumbnails[t.id], t.displayTitle, 24)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (members.size <= 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun groupLabel(group: TabGroup, count: Int): String = group.name.ifBlank { pluralStringResource(R.plurals.tab_count, count, count) }

/** A group opened up: rename, recolour, its tabs, and a button to add one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSheet(
    group: TabGroup,
    members: List<TabState>,
    currentTabId: Long?,
    allGroups: List<TabGroup>,
    thumbnails: Map<Long, Bitmap>,
    actions: TabActions,
    onDismiss: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(Color(TabGroup.COLORS[group.colorIndex])))
                val placeholder = groupLabel(group, members.size)
                BasicTextField(
                    value = group.name,
                    onValueChange = { actions.renameGroup(group.id, it) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    decorationBox = { inner ->
                        if (group.name.isEmpty()) Text(placeholder, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    },
                )
                IconButton(onClick = { actions.newTabInGroup(group.id) }) { Icon(Icons.Outlined.Add, stringResource(R.string.new_tab_in_group)) }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.menu)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.ungroup)) }, onClick = {
                            menuOpen = false
                            actions.ungroup(group.id)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.close_group)) }, onClick = {
                            menuOpen = false
                            actions.closeGroup(group.id)
                        })
                    }
                }
            }
            // Chrome's colour picker for the group. Each swatch keeps a 48dp touch target.
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                TabGroup.COLORS.forEachIndexed { index, argb ->
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable { actions.recolorGroup(group.id, index) }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(argb)), contentAlignment = Alignment.Center) {
                            if (index == group.colorIndex) Icon(Icons.Filled.Check, stringResource(R.string.color_selected), Modifier.size(16.dp), tint = Color.White)
                        }
                    }
                }
            }
            HorizontalDivider()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.heightIn(max = 520.dp),
            ) {
                items(members, key = { it.id }) { tab -> TabCard(tab, thumbnails[tab.id], tab.id == currentTabId, allGroups, actions) }
            }
        }
    }
}
