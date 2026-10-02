package org.tamodak.hisar.ui.navigation

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import org.tamodak.hisar.ui.bookmarks.BookmarksRoute
import org.tamodak.hisar.ui.browser.BrowserController
import org.tamodak.hisar.ui.browser.PromptDialog
import org.tamodak.hisar.ui.browser.BrowserScreen
import org.tamodak.hisar.ui.browser.ClearDataDialog
import org.tamodak.hisar.ui.browser.ImageSearchSheet
import org.tamodak.hisar.ui.browser.MessageAction
import org.tamodak.hisar.ui.browser.UserMessage
import org.tamodak.hisar.ui.components.FaviconLoader
import org.tamodak.hisar.ui.components.LocalFaviconLoader
import org.tamodak.hisar.ui.downloads.DownloadsRoute
import org.tamodak.hisar.ui.history.HistoryRoute
import org.tamodak.hisar.ui.recenttabs.RecentTabsRoute
import org.tamodak.hisar.ui.settings.SettingsRoute
import org.tamodak.hisar.ui.tabs.GroupSheet
import org.tamodak.hisar.ui.tabs.TabActions
import org.tamodak.hisar.ui.tabs.TabSwitcherScreen

/** Hisar's screens. The browser is always at the bottom of the back stack. */
@Serializable
sealed interface Route : NavKey {
    @Serializable data object Browser : Route
    @Serializable data object Tabs : Route
    @Serializable data object History : Route
    @Serializable data object Bookmarks : Route
    @Serializable data object Downloads : Route
    @Serializable data object RecentTabs : Route
    @Serializable data object Settings : Route
}

/**
 * The app's top level: Navigation 3 between screens, and what floats over all of them —
 * snackbars, the shared dialogs and sheets, and full-screen video.
 */
@Composable
fun HisarApp(controller: BrowserController, faviconLoader: FaviconLoader) {
    val viewModel = controller.viewModel
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(Route.Browser)

    fun navigate(route: Route) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    fun backToBrowser() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    val openUrl: (String) -> Unit = { url ->
        viewModel.navigate(url)
        backToBrowser()
    }
    DownloadCompleteEffect(onComplete = viewModel::onDownloadComplete)

    CompositionLocalProvider(LocalFaviconLoader provides faviconLoader) {
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    // Each screen's ViewModel lives as long as the screen is on the back stack.
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<Route.Browser> { BrowserScreen(viewModel, controller, ::navigate) }
                    entry<Route.Tabs> {
                        TabSwitcherScreen(
                            state = state,
                            thumbnails = controller.thumbnails,
                            actions = remember(controller) { ControllerTabActions(controller, onSelected = ::backToBrowser) },
                            onBack = {
                                state.currentTab?.let { controller.selectTab(it.id) }
                                backToBrowser()
                            },
                            onOpenRecentTabs = { navigate(Route.RecentTabs) },
                            onOpenSettings = { navigate(Route.Settings) },
                        )
                    }
                    entry<Route.History> {
                        HistoryRoute(hiltViewModel(), onBack = ::backToBrowser, onOpen = openUrl, onDeleteBrowsingData = { controller.clearDataOpen = true })
                    }
                    entry<Route.Bookmarks> {
                        BookmarksRoute(
                            hiltViewModel(),
                            onBack = ::backToBrowser,
                            onOpen = openUrl,
                            onOpenInNewTab = { url ->
                                viewModel.newTab(url)
                                backToBrowser()
                            },
                            onShare = controller::share,
                        )
                    }
                    entry<Route.Downloads> {
                        DownloadsRoute(
                            hiltViewModel(),
                            onBack = ::backToBrowser,
                            onOpenFile = controller::openDownload,
                            onOpenPage = { page ->
                                viewModel.openOfflinePage(page)
                                backToBrowser()
                            },
                        )
                    }
                    entry<Route.RecentTabs> {
                        RecentTabsRoute(hiltViewModel(), onBack = ::backToBrowser, onReopen = { url ->
                            viewModel.reopenClosed(url)
                            backToBrowser()
                        })
                    }
                    entry<Route.Settings> {
                        SettingsRoute(hiltViewModel(), onBack = { backStack.removeLastOrNull() }, onDeleteBrowsingData = { controller.clearDataOpen = true })
                    }
                },
            )

            Messages(
                messages = state.messages,
                onShown = viewModel::onMessageShown,
                onAction = { action ->
                    when (action) {
                        is MessageAction.OpenDownload -> controller.openDownload(action.downloadId)
                        MessageAction.OpenDownloads -> navigate(Route.Downloads)
                        else -> viewModel.onMessageAction(action)
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            if (controller.clearDataOpen) {
                ClearDataDialog(
                    onDismiss = { controller.clearDataOpen = false },
                    onConfirm = { history, cookies, cache ->
                        controller.clearDataOpen = false
                        controller.clearBrowsingData(history, cookies, cache)
                    },
                )
            }
            if (controller.imageSearchOpen) {
                ImageSearchSheet(onDismiss = controller::dismissImageSearch, onTakePhoto = controller::takePhoto, onPickPhoto = controller::pickPhoto)
            }
            controller.openGroupId?.let { id -> state.groups.firstOrNull { it.id == id } }?.let { group ->
                GroupSheet(
                    group = group,
                    members = state.tabsIn(group),
                    currentTabId = state.currentTabId,
                    allGroups = state.groups,
                    thumbnails = controller.thumbnails,
                    actions = remember(controller) { ControllerTabActions(controller, onSelected = ::backToBrowser) },
                    onDismiss = { controller.openGroupId = null },
                )
            }
            // A page's own dialog (alert, confirm, a <select>'s options, a date) over the page.
            controller.prompt?.let { prompt ->
                PromptDialog(prompt, onDone = { controller.onPromptDone(prompt) })
            }
        }
    }
}

/** Shows the ViewModel's messages one at a time and reports each back once it is gone. */
@Composable
private fun Messages(
    messages: List<UserMessage>,
    onShown: (Long) -> Unit,
    onAction: (MessageAction) -> Unit,
    modifier: Modifier,
) {
    val host = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val message = messages.firstOrNull()
    LaunchedEffect(message?.id) {
        if (message == null) return@LaunchedEffect
        val result = host.showSnackbar(
            message = resources.getString(message.text, *message.args.toTypedArray()),
            actionLabel = message.actionLabel?.let(resources::getString),
            // Short: gone after a few seconds even when it carries an action such as Undo.
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) message.action?.let(onAction)
        onShown(message.id)
    }
    SnackbarHost(host, modifier.navigationBarsPadding().imePadding().padding(bottom = 8.dp))
}

/**
 * DownloadManager's completion broadcast, received while the UI is on screen. Registered and
 * unregistered with the composition, as the broadcast guidance recommends for UI-only receivers.
 */
@Composable
private fun DownloadCompleteEffect(onComplete: (Long) -> Unit) {
    val context = LocalContext.current
    val currentOnComplete by rememberUpdatedState(onComplete)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                currentOnComplete(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1))
            }
        }
        // The broadcast comes from the system's download provider, which is another app.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
}

/** Tab-grid actions, on the browser's controller and ViewModel. */
private class ControllerTabActions(private val controller: BrowserController, private val onSelected: () -> Unit) : TabActions {
    private val vm get() = controller.viewModel
    override fun select(tabId: Long) {
        controller.selectTab(tabId)
        controller.openGroupId = null
        onSelected()
    }
    override fun close(tabId: Long) = vm.closeTab(tabId)
    override fun newTab() {
        controller.newTab()
        controller.openGroupId = null
        onSelected()
    }
    override fun closeAll() = vm.closeAllTabs()
    override fun createGroup(tabId: Long) {
        controller.openGroupId = vm.createGroup(tabId).id
    }
    override fun addToGroup(tabId: Long, groupId: Long) = vm.addToGroup(tabId, groupId)
    override fun removeFromGroup(tabId: Long) = vm.removeFromGroup(tabId)
    override fun openGroup(groupId: Long) {
        controller.openGroupId = groupId
    }
    override fun closeGroup(groupId: Long) = vm.closeGroup(groupId)
    override fun ungroup(groupId: Long) {
        vm.ungroup(groupId)
        controller.openGroupId = null
    }
    override fun renameGroup(groupId: Long, name: String) = vm.renameGroup(groupId, name)
    override fun recolorGroup(groupId: Long, colorIndex: Int) = vm.recolorGroup(groupId, colorIndex)
    override fun newTabInGroup(groupId: Long) {
        vm.newTabInGroup(groupId)
        controller.openGroupId = null
        onSelected()
    }
}
