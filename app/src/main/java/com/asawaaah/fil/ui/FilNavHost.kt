package com.asawaaah.fil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.asawaaah.fil.ui.glass.GlassTab
import com.asawaaah.fil.ui.glass.GlassTabBar
import com.asawaaah.fil.ui.glass.LocalBottomOverlay
import com.asawaaah.fil.ui.glass.TabBarReservedHeight
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.asawaaah.fil.AppContainer
import com.asawaaah.fil.PendingAction
import com.asawaaah.fil.ui.feed.FeedScreen
import com.asawaaah.fil.ui.feed.ListSelection
import com.asawaaah.fil.ui.importopml.ImportScreen
import com.asawaaah.fil.ui.reader.ReaderScreen
import com.asawaaah.fil.ui.settings.SettingsScreen
import com.asawaaah.fil.ui.sources.SourcesScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab("feed", "Fil", Icons.AutoMirrored.Outlined.Article, Icons.AutoMirrored.Filled.Article),
    Tab("saved", "À lire", Icons.Outlined.BookmarkBorder, Icons.Filled.Bookmark),
    Tab("sources", "Sources", Icons.Outlined.RssFeed, Icons.Filled.RssFeed),
    Tab("settings", "Réglages", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun FilNavHost(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = tabs.any { it.route == route }
    var prefillUrl by remember { mutableStateOf<String?>(null) }

    val pending by container.pendingAction.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        when (val p = pending) {
            PendingAction.OpenImport -> nav.navigate("import") { launchSingleTop = true }
            is PendingAction.OpenArticle -> nav.navigate("reader/${p.id}")
            is PendingAction.OpenFeed -> nav.navigate("list/feed/${p.id}")
            is PendingAction.AddSource -> {
                prefillUrl = p.url
                nav.switchTab("sources")
            }
            null -> Unit
        }
        if (pending != null) container.pendingAction.value = null
    }

    // Everything drawn by the screens is recorded so the floating tab bar can refract it.
    val rootBackdrop = rememberLayerBackdrop()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Measured, because the bar grows with the system font size.
    var barHeight by remember { mutableStateOf(TabBarReservedHeight) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalBottomOverlay provides if (showBar) maxOf(barHeight + 8.dp, TabBarReservedHeight + navInset) else 0.dp) {
        Box(Modifier.fillMaxSize().layerBackdrop(rootBackdrop).background(MaterialTheme.colorScheme.background)) {
            NavHost(nav, startDestination = "feed") {
                composable("feed") {
                    FeedScreen(
                        initial = ListSelection.Main,
                        onOpenArticle = { nav.navigate("reader/$it") },
                        onBack = null,
                        onGoToSources = { nav.switchTab("sources") },
                        onImportOpml = { nav.navigate("import") },
                        onOpenFeed = { nav.navigate("list/feed/$it") },
                    )
                }
                composable("saved") {
                    FeedScreen(
                        initial = ListSelection.Saved,
                        onOpenArticle = { nav.navigate("reader/$it") },
                        onBack = null,
                        onGoToSources = { nav.switchTab("sources") },
                        onImportOpml = { nav.navigate("import") },
                        onOpenFeed = { nav.navigate("list/feed/$it") },
                    )
                }
                composable("sources") {
                    SourcesScreen(
                        prefillUrl = prefillUrl,
                        onPrefillConsumed = { prefillUrl = null },
                        onOpenFeed = { nav.navigate("list/feed/$it") },
                        onOpenFolder = { nav.navigate("list/folder/$it") },
                        onImportOpml = { nav.navigate("import") },
                    )
                }
                composable("settings") {
                    SettingsScreen(onImportOpml = { nav.navigate("import") }, onOpenTopics = { nav.navigate("topics") })
                }
                composable("topics") {
                    com.asawaaah.fil.ui.settings.TopicsScreen(onBack = { nav.popBackStack() })
                }
                composable("list/feed/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    FeedScreen(
                        initial = ListSelection.Feed(id),
                        onOpenArticle = { nav.navigate("reader/$it") },
                        onBack = { nav.popBackStack() },
                        onGoToSources = { nav.switchTab("sources") },
                        onImportOpml = { nav.navigate("import") },
                        onOpenFeed = { nav.navigate("list/feed/$it") },
                    )
                }
                composable("list/folder/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    FeedScreen(
                        initial = ListSelection.Folder(id),
                        onOpenArticle = { nav.navigate("reader/$it") },
                        onBack = { nav.popBackStack() },
                        onGoToSources = { nav.switchTab("sources") },
                        onImportOpml = { nav.navigate("import") },
                        onOpenFeed = { nav.navigate("list/feed/$it") },
                    )
                }
                composable("reader/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    ReaderScreen(
                        articleId = entry.arguments?.getLong("id") ?: 0L,
                        onBack = { nav.popBackStack() },
                        onOpenSettings = { nav.switchTab("settings") },
                        onOpenFeed = { nav.navigate("list/feed/$it") },
                    )
                }
                composable("import") {
                    ImportScreen(
                        onBack = { nav.popBackStack() },
                        onDone = {
                            nav.popBackStack()
                            nav.switchTab("feed")
                        },
                    )
                }
            }
        }
        }
        if (showBar) {
            GlassTabBar(
                backdrop = rootBackdrop,
                tabs = tabs.map { GlassTab(it.label, it.icon, it.selectedIcon) },
                selectedIndex = tabs.indexOfFirst { it.route == route },
                onSelect = { nav.switchTab(tabs[it].route) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { barHeight = with(density) { it.height.toDp() } },
            )
        }
    }
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
