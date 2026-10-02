package org.tamodak.hisar.ui.browser

import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.FormatColorText
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.tamodak.hisar.R
import org.tamodak.hisar.data.model.OfflinePage
import org.tamodak.hisar.data.model.TabGroup
import org.tamodak.hisar.search.Omnibox
import org.tamodak.hisar.ui.components.SiteIcon
import org.tamodak.hisar.ui.components.TabCountIcon
import org.tamodak.hisar.ui.components.hostOf
import org.tamodak.hisar.ui.navigation.Route

/** The browser: toolbar, the current tab's page or new tab page, and the group strip. */
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    controller: BrowserController,
    onNavigate: (Route) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()
    val bookmarked by viewModel.bookmarkedUrls.collectAsStateWithLifecycle()
    val offlinePages by viewModel.offlinePages.collectAsStateWithLifecycle()

    val tab = state.currentTab
    val focusManager = LocalFocusManager.current
    var omniboxText by remember { mutableStateOf(TextFieldValue()) }
    val offlinePage = tab?.offlinePageId?.let { id -> offlinePages.firstOrNull { it.id == id } }

    // Later handlers win, so the most specific comes last.
    BackHandler(enabled = tab?.canGoBack == true) { controller.goBack() }
    BackHandler(enabled = controller.findInPageOpen) { controller.findInPageOpen = false }
    BackHandler(enabled = controller.omniboxFocused) { focusManager.clearFocus() }

    val showToolbar = controller.toolbarVisible || controller.omniboxFocused || controller.findInPageOpen || tab?.showNewTabPage == true
    val group = tab?.let(state::groupOf)

    // A Surface rather than a plain background, so icons and text take the theme's content colour.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            // The status bar strip stays when the toolbar slides away, so the page never runs under it.
            Spacer(Modifier.statusBarsPadding())
            AnimatedVisibility(
                visible = showToolbar,
                enter = expandVertically(expandFrom = Alignment.Top),
                exit = shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                val webView = controller.webView(tab?.id)
                if (controller.findInPageOpen && webView != null && tab?.showNewTabPage == false) {
                    FindInPageBar(webView, onClose = { controller.findInPageOpen = false })
                } else {
                    Toolbar(
                        controller = controller,
                        tab = tab,
                        tabCount = state.tabs.size,
                        offlinePage = offlinePage,
                        bookmarked = tab != null && tab.url in bookmarked,
                        simpleMode = settings.simpleMode,
                        text = omniboxText,
                        onTextChange = {
                            omniboxText = it
                            viewModel.onOmniboxInput(it.text)
                        },
                        onNavigate = onNavigate,
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    tab == null -> Unit
                    tab.showNewTabPage -> NewTabPage(
                        tiles = tiles,
                        onSearchBoxVisibleChange = { controller.ntpSearchBoxVisible = it },
                        onSearchBoxClick = controller::focusOmnibox,
                        onVoiceSearch = { controller.startVoiceSearch() },
                        onImageSearch = { controller.openImageSearch() },
                        onOpen = viewModel::navigate,
                        onOpenInNewTab = viewModel::openInBackground,
                        onCopyLink = controller::copy,
                        onRemoveTile = viewModel::removeShortcut,
                        onEditTile = viewModel::editShortcut,
                    )
                    else -> WebContent(controller, tab)
                }
                if (controller.omniboxFocused) {
                    SuggestionList(
                        tab = tab,
                        offlinePage = offlinePage,
                        input = omniboxText.text,
                        suggestions = suggestions,
                        onNavigate = { url ->
                            viewModel.navigate(url)
                            focusManager.clearFocus()
                        },
                        onRefine = {
                            omniboxText = TextFieldValue("$it ", TextRange(it.length + 1))
                            viewModel.onOmniboxInput(omniboxText.text)
                        },
                        onCopy = controller::copy,
                        onShare = controller::share,
                    )
                }
            }
            // Chrome's bottom strip for a tab in a group: the group's tabs, and a button for another.
            if (group != null && tab != null && showToolbar && !controller.omniboxFocused) {
                GroupStrip(
                    group = group,
                    tabs = state.tabsIn(group),
                    currentId = tab.id,
                    onExpand = { controller.openGroupId = group.id },
                    onSelect = controller::selectTab,
                    onAdd = { viewModel.newTabInGroup(group.id) },
                )
            }
        }
    }

    controller.linkMenu?.let { target ->
        LinkMenuSheet(
            target = target,
            onDismiss = { controller.linkMenu = null },
            onOpenInNewTab = viewModel::openInBackground,
            onOpenInGroup = viewModel::openInGroup,
            onCopy = controller::copy,
            onShare = { controller.share(it) },
            onDownload = { controller.download(it, referer = tab?.url) },
            onSearchImage = controller::searchImageUrl,
        )
    }
    if (controller.pageInfoOpen && tab != null) PageInfoSheet(tab, onDismiss = { controller.pageInfoOpen = false })
}

/**
 * Hosts the tab's WebView, moving it here from wherever it was last attached, inside a
 * pull-to-refresh layout.
 */
@Composable
private fun WebContent(controller: BrowserController, tab: TabState) {
    val webView = controller.webView(tab.id) ?: return
    val spinner = MaterialTheme.colorScheme.primary.toArgb()
    val spinnerBackground = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()
    val loading = tab.loading
    // One host per WebView instance: a WebView replaced after its renderer died (or a tab switch)
    // gets a fresh host rather than relying on the old one noticing the swap.
    key(webView) {
    AndroidView(
        factory = { context ->
            SwipeRefreshLayout(context).apply {
                setColorSchemeColors(spinner)
                setProgressBackgroundColorSchemeColor(spinnerBackground)
            }
        },
        update = { layout ->
            if (layout.getChildAt(layout.childCount - 1) !== webView) {
                // SwipeRefreshLayout keeps its spinner as a child too; only the WebView is swapped.
                (webView.parent as? ViewGroup)?.removeView(webView)
                for (i in layout.childCount - 1 downTo 0) {
                    if (layout.getChildAt(i) is WebView) layout.removeViewAt(i)
                }
                layout.addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            layout.setOnRefreshListener { webView.reload() }
            // Pull only when the page itself is at the top, not when it merely has room to scroll.
            layout.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
            if (!loading) layout.isRefreshing = false
        },
        // The WebView belongs to the controller and outlives this view; detach it, never destroy it here.
        onRelease = { layout -> layout.removeView(webView) },
        modifier = Modifier.fillMaxSize(),
    )
    }
}

@Composable
private fun Toolbar(
    controller: BrowserController,
    tab: TabState?,
    tabCount: Int,
    offlinePage: OfflinePage?,
    bookmarked: Boolean,
    simpleMode: Boolean,
    text: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    onNavigate: (Route) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val focused = controller.omniboxFocused
    // Swiping the toolbar sideways moves to the neighbouring tab, as in Chrome.
    var dragX by remember { mutableFloatStateOf(0f) }
    val switchDistance = with(LocalDensity.current) { 72.dp.toPx() }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 4.dp)
                .pointerInput(focused) {
                    if (focused) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { dragX = 0f },
                        onDragEnd = {
                            when {
                                dragX < -switchDistance -> controller.viewModel.switchToNeighbour(+1)
                                dragX > switchDistance -> controller.viewModel.switchToNeighbour(-1)
                            }
                            dragX = 0f
                        },
                        onDragCancel = { dragX = 0f },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragX += amount
                        },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (focused) {
                IconButton(onClick = { focusManager.clearFocus() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
            } else {
                IconButton(onClick = controller::goHome) {
                    Icon(Icons.Outlined.Home, stringResource(R.string.home))
                }
            }
            OmniboxField(
                controller,
                tab,
                offlinePage,
                text,
                onTextChange,
                Modifier.weight(1f).graphicsLayer { translationX = dragX * 0.4f },
            )
            if (!focused) {
                IconButton(onClick = {
                    controller.captureThumbnail()
                    onNavigate(Route.Tabs)
                }) {
                    TabCountIcon(tabCount)
                }
                OverflowMenu(controller, tab, bookmarked, simpleMode, onNavigate)
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
        if (tab != null && tab.loading && !tab.showNewTabPage && !focused) {
            LinearProgressIndicator(
                progress = { tab.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
        } else {
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun OmniboxField(
    controller: BrowserController,
    tab: TabState?,
    offlinePage: OfflinePage?,
    text: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    modifier: Modifier,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val focused = controller.omniboxFocused
    val pageUrl = tab?.url?.takeUnless { tab.showNewTabPage }.orEmpty()
    // On the new tab page the page's own search box stands in for this one until scrolled away.
    val hiddenForNtp = tab?.showNewTabPage == true && controller.ntpSearchBoxVisible && !focused
    val alpha by animateFloatAsState(if (hiddenForNtp) 0f else 1f, label = "omnibox")

    LaunchedEffect(controller.omniboxFocusRequested) {
        if (controller.omniboxFocusRequested) {
            controller.omniboxFocusRequested = false
            focusRequester.requestFocus()
        }
    }

    Row(
        modifier.alpha(alpha).height(46.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The site-info button, as in Chrome: tapping it opens the page info sheet.
        val secure = pageUrl.startsWith("https://") || offlinePage != null
        val leading: ImageVector = when {
            focused || pageUrl.isEmpty() -> Icons.Outlined.Search
            offlinePage != null -> Icons.Outlined.OfflinePin
            tab?.readerMode == true -> Icons.AutoMirrored.Outlined.Article
            secure -> Icons.Outlined.Lock
            else -> Icons.Outlined.Info
        }
        Box(
            Modifier
                .padding(start = 4.dp)
                .size(40.dp)
                .clip(CircleShape)
                .clickable(enabled = !focused && tab?.onWebPage == true) { controller.pageInfoOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                leading,
                contentDescription = stringResource(R.string.page_info),
                modifier = Modifier.size(20.dp),
                tint = if (!focused && pageUrl.isNotEmpty() && !secure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val resting = if (offlinePage != null) stringResource(R.string.offline_label, hostOf(offlinePage.url)) else Omnibox.display(pageUrl)
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = if (focused) text else TextFieldValue(resting),
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = {
                    controller.viewModel.navigateInput(text.text)
                    focusManager.clearFocus()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focus ->
                        if (focus.isFocused && !controller.omniboxFocused) {
                            // Chrome selects the whole address on focus, ready to be replaced.
                            val full = offlinePage?.url ?: pageUrl
                            onTextChange(TextFieldValue(full, TextRange(0, full.length)))
                        }
                        controller.omniboxFocused = focus.isFocused
                    },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if ((if (focused) text.text else resting).isEmpty()) {
                            Text(
                                stringResource(R.string.address_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            // While resting, a tap must focus the field without also placing the cursor, or the
            // select-all above is undone and typing lands in the middle of the old address.
            if (!focused) {
                Box(
                    Modifier.matchParentSize().clickable(enabled = !hiddenForNtp, interactionSource = null, indication = null) {
                        focusRequester.requestFocus()
                    },
                )
            }
        }
        when {
            focused && text.text.isNotEmpty() -> IconButton(onClick = { onTextChange(TextFieldValue()) }) {
                Icon(Icons.Outlined.Close, stringResource(R.string.clear), Modifier.size(20.dp))
            }
            // Empty and editing: Chrome offers voice and image search here.
            focused -> Row {
                IconButton(onClick = {
                    focusManager.clearFocus()
                    controller.startVoiceSearch()
                }) { Icon(Icons.Outlined.Mic, stringResource(R.string.voice_search), Modifier.size(22.dp)) }
                IconButton(onClick = {
                    focusManager.clearFocus()
                    controller.openImageSearch()
                }) { Icon(Icons.Outlined.CameraAlt, stringResource(R.string.image_search), Modifier.size(22.dp)) }
            }
            else -> Spacer(Modifier.width(16.dp))
        }
    }
}

@Composable
private fun OverflowMenu(
    controller: BrowserController,
    tab: TabState?,
    bookmarked: Boolean,
    simpleMode: Boolean,
    onNavigate: (Route) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val viewModel = controller.viewModel
    val onPage = tab != null && !tab.showNewTabPage && tab.url.isNotEmpty()
    val onWeb = tab?.onWebPage == true
    fun act(action: () -> Unit) {
        open = false
        action()
    }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.width(272.dp)) {
            // Chrome's icon row: forward, bookmark, download page, page info, reload.
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { act(controller::goForward) }, enabled = tab?.canGoForward == true) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.forward))
                }
                IconButton(onClick = { act { tab?.let { viewModel.toggleBookmark(it.url, it.title) } } }, enabled = onWeb) {
                    if (bookmarked) {
                        Icon(Icons.Filled.Star, stringResource(R.string.bookmark_remove), tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Icon(Icons.Outlined.StarBorder, stringResource(R.string.bookmark_add))
                    }
                }
                IconButton(onClick = { act(controller::savePageOffline) }, enabled = onWeb && tab?.readerMode != true) {
                    Icon(Icons.Outlined.DownloadForOffline, stringResource(R.string.download_page))
                }
                IconButton(onClick = { act { controller.pageInfoOpen = true } }, enabled = onWeb) {
                    Icon(Icons.Outlined.Info, stringResource(R.string.page_info))
                }
                if (tab?.loading == true && onPage) {
                    IconButton(onClick = { act { controller.stop() } }) { Icon(Icons.Outlined.Close, stringResource(R.string.stop)) }
                } else {
                    IconButton(onClick = { act { controller.reload() } }, enabled = onPage) { Icon(Icons.Outlined.Refresh, stringResource(R.string.reload)) }
                }
            }
            HorizontalDivider()
            MenuItem(Icons.Outlined.Add, R.string.new_tab) { act(controller::newTab) }
            MenuItem(Icons.Outlined.History, R.string.history) { act { onNavigate(Route.History) } }
            MenuItem(Icons.Outlined.Download, R.string.downloads) { act { onNavigate(Route.Downloads) } }
            MenuItem(Icons.Outlined.Restore, R.string.recent_tabs) { act { onNavigate(Route.RecentTabs) } }
            MenuItem(Icons.Outlined.Delete, R.string.delete_browsing_data) { act { controller.clearDataOpen = true } }
            MenuItem(Icons.Outlined.Bookmarks, R.string.bookmarks) { act { onNavigate(Route.Bookmarks) } }
            HorizontalDivider()
            MenuItem(Icons.Outlined.Share, R.string.share, enabled = onWeb) { act { tab?.let { controller.share(it.url, it.title) } } }
            MenuItem(Icons.Outlined.FindInPage, R.string.find_in_page, enabled = onPage) { act { controller.findInPageOpen = true } }
            MenuItem(Icons.Outlined.Translate, R.string.translate, enabled = onWeb) { act { tab?.let { viewModel.translate(it.id) } } }
            if (tab?.readerMode == true) {
                MenuItem(Icons.AutoMirrored.Outlined.Article, R.string.show_original) { act { viewModel.showOriginal(tab.id) } }
            } else {
                MenuItem(Icons.AutoMirrored.Outlined.Article, R.string.simplified_view, enabled = onWeb) { act(controller::showSimplifiedView) }
            }
            MenuItem(Icons.Outlined.AddToHomeScreen, R.string.add_to_home, enabled = onWeb) { act(controller::addToHomeScreen) }
            CheckMenuItem(Icons.Outlined.DesktopWindows, R.string.desktop_site, tab?.desktopMode == true, enabled = onWeb) {
                act { controller.setDesktopMode(tab?.desktopMode != true) }
            }
            CheckMenuItem(Icons.Outlined.FormatColorText, R.string.simple_mode, simpleMode) {
                act { viewModel.setSimpleMode(!simpleMode) }
            }
            HorizontalDivider()
            MenuItem(Icons.Outlined.Settings, R.string.settings) { act { onNavigate(Route.Settings) } }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null) }, enabled = enabled, onClick = onClick)
}

@Composable
private fun CheckMenuItem(icon: ImageVector, label: Int, checked: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
        enabled = enabled,
        onClick = onClick,
    )
}

/** Chrome's bottom tab strip for a grouped tab: expand, the group's tabs, add a tab. */
@Composable
private fun GroupStrip(
    group: TabGroup,
    tabs: List<TabState>,
    currentId: Long,
    onExpand: () -> Unit,
    onSelect: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExpand) {
                Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.show_group), tint = Color(TabGroup.COLORS[group.colorIndex]))
            }
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                items(tabs, key = { it.id }) { t ->
                    // 48dp: the minimum touch target.
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (t.id == currentId) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { onSelect(t.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (t.showNewTabPage) {
                            Icon(painterResource(R.drawable.ic_hisar_logo), stringResource(R.string.new_tab), Modifier.size(20.dp), tint = Color.Unspecified)
                        } else {
                            SiteIcon(t.url, t.displayTitle, size = 20.dp, icon = t.favicon)
                        }
                    }
                }
            }
            IconButton(onClick = onAdd) { Icon(Icons.Outlined.Add, stringResource(R.string.new_tab_in_group)) }
        }
    }
}
