package space.sgu.next.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

/** Unfolded Fold 8 (and tablets / landscape) → side rail + two panes. */
@Composable
fun isWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

private data class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab("캐릭터", Icons.Outlined.People, Icons.Filled.People),
    Tab("참고 자료", Icons.Outlined.CollectionsBookmark, Icons.Filled.CollectionsBookmark),
    Tab("설정", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun SguApp() {
    val nav = rememberNavController()
    val dur = 280
    NavHost(
        navController = nav,
        startDestination = "home",
        enterTransition = { slideInHorizontally(tween(dur)) { it / 4 } + fadeIn(tween(dur)) },
        exitTransition = { slideOutHorizontally(tween(dur)) { -it / 8 } + fadeOut(tween(dur / 2)) },
        popEnterTransition = { slideInHorizontally(tween(dur)) { -it / 8 } + fadeIn(tween(dur)) },
        popExitTransition = { slideOutHorizontally(tween(dur)) { it / 4 } + fadeOut(tween(dur / 2)) },
    ) {
        composable("home") {
            HomeScreen(openCharacter = { id -> nav.navigate("character/$id") })
        }
        composable("character/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
            EditorScreen(
                characterId = entry.arguments?.getInt("id") ?: 0,
                onBack = { nav.popBackStack() },
            )
        }
    }
}

@Composable
private fun HomeScreen(openCharacter: (Int) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableIntStateOf(0) }
    val wide = isWide()

    val content: @Composable () -> Unit = {
        when (tab) {
            0 -> if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(0.42f)) {
                        CharactersScreen(selectedId = selectedId, onOpen = { selectedId = it })
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.weight(0.58f)) {
                        if (selectedId == 0) EmptyPane("왼쪽에서 캐릭터를 고르세요")
                        else EditorScreen(characterId = selectedId, onBack = null)
                    }
                }
            } else {
                CharactersScreen(selectedId = 0, onOpen = openCharacter)
            }
            1 -> ReferencesScreen()
            else -> SettingsScreen()
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                tabs.forEachIndexed { i, t ->
                    NavigationRailItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(if (tab == i) t.selectedIcon else t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f)) { content() }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(if (tab == i) t.selectedIcon else t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) { content() }
        }
    }
}
