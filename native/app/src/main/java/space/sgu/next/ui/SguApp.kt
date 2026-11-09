package space.sgu.next.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import space.sgu.next.data.CharacterStore

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
    fun openField(id: String, key: String) = nav.navigate("field/$id/${Uri.encode(key)}")
    NavHost(
        navController = nav,
        startDestination = "home",
        modifier = Modifier.background(Sgu.Bg),
        enterTransition = { slideInHorizontally(tween(dur)) { it / 4 } + fadeIn(tween(dur)) },
        exitTransition = { slideOutHorizontally(tween(dur)) { -it / 8 } + fadeOut(tween(dur / 2)) },
        popEnterTransition = { slideInHorizontally(tween(dur)) { -it / 8 } + fadeIn(tween(dur)) },
        popExitTransition = { slideOutHorizontally(tween(dur)) { it / 4 } + fadeOut(tween(dur / 2)) },
    ) {
        composable("home") {
            HomeScreen(
                openCharacter = { id -> nav.navigate("character/$id") },
                openField = ::openField,
                openSettings = { route -> nav.navigate(route) },
                openPreset = { id, pid -> nav.navigate("genpreset/$id/$pid") },
            )
        }
        val back: () -> Unit = { nav.popBackStack() }
        val go: (String) -> Unit = { route -> nav.navigate(route) }
        composable("providers") { ProvidersScreen(open = go, onBack = back) }
        composable("provider/{id}") { e -> ProviderScreen(e.arguments?.getString("id") ?: "", open = go, onBack = back) }
        composable("models") { ModelsScreen(open = go, onBack = back) }
        composable("model/{id}") { e -> ModelScreen(e.arguments?.getString("id") ?: "", onBack = back) }
        composable("processors") { ProcessorsScreen(open = go, onBack = back) }
        composable("backup") { BackupScreen(onBack = back) }
        composable("character/{id}") { entry ->
            val id = entry.arguments?.getString("id") ?: ""
            EditorScreen(characterId = id, onBack = { nav.popBackStack() }, openField = { key -> openField(id, key) },
                openPreset = { pid -> nav.navigate("genpreset/$id/$pid") }, openRoute = go)
        }
        composable("genpreset/{id}/{pid}") { entry ->
            val id = entry.arguments?.getString("id") ?: ""
            GenPresetScreen(id, entry.arguments?.getString("pid") ?: "", openField = { key -> openField(id, key) }, onBack = { nav.popBackStack() })
        }
        composable("regex/{id}/{i}/{new}") { e ->
            RegexScreen(e.arguments?.getString("id") ?: "", e.arguments?.getString("i")?.toIntOrNull() ?: -1, e.arguments?.getString("new") == "1", onBack = back)
        }
        composable("lore/{id}/{i}/{new}") { e ->
            val id = e.arguments?.getString("id") ?: ""
            LoreScreen(id, e.arguments?.getString("i")?.toIntOrNull() ?: -1, e.arguments?.getString("new") == "1", openField = { key -> openField(id, key) }, onBack = back)
        }
        composable("gentemplates/{id}") { e -> TemplateManagerScreen(e.arguments?.getString("id") ?: "", onBack = back) }
        composable("genpick/{id}") { e ->
            val id = e.arguments?.getString("id") ?: ""
            ImagePickerScreen(id, openPreset = { pid -> nav.navigate("genpreset/$id/$pid") }, onBack = back)
        }
        composable("bias/{id}") { e -> BiasScreen(e.arguments?.getString("id") ?: "", onBack = back) }
        composable("onboarding/{id}") { e ->
            val id = e.arguments?.getString("id") ?: ""
            OnboardingScreen(id, openField = { key -> openField(id, key) }, onBack = back)
        }
        composable("field/{id}/{key}") { entry ->
            FieldEditorScreen(
                characterId = entry.arguments?.getString("id") ?: "",
                key = Uri.decode(entry.arguments?.getString("key") ?: ""),
                onBack = { nav.popBackStack() },
            )
        }
    }
}

@Composable
private fun HomeScreen(openCharacter: (String) -> Unit, openField: (String, String) -> Unit, openSettings: (String) -> Unit, openPreset: (String, String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var picked by rememberSaveable { mutableStateOf("") }
    val wide = isWide()
    // A deleted character must not stay open in the right pane.
    val selectedId = picked.takeIf { p -> CharacterStore.items.any { it.id == p } } ?: ""

    val content: @Composable () -> Unit = {
        when (tab) {
            0 -> if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(0.42f).background(Sgu.Drawer)) {
                        CharactersScreen(selectedId = selectedId, onOpen = { picked = it }, inPane = true)
                    }
                    VerticalDivider(color = Sgu.Line)
                    Box(Modifier.weight(0.58f)) {
                        if (selectedId.isEmpty()) EmptyPane("왼쪽에서 캐릭터를 고르세요")
                        else EditorScreen(characterId = selectedId, onBack = null, openField = { key -> openField(selectedId, key) },
                            openPreset = { pid -> openPreset(selectedId, pid) }, openRoute = openSettings)
                    }
                }
            } else {
                CharactersScreen(selectedId = "", onOpen = openCharacter, inPane = false)
            }
            1 -> ReferencesScreen()
            else -> SettingsScreen(open = openSettings)
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize().background(Sgu.Bg)) {
            NavigationRail(containerColor = Sgu.Drawer) {
                tabs.forEachIndexed { i, t ->
                    NavigationRailItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(if (tab == i) t.selectedIcon else t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Sgu.Ink, selectedTextColor = Sgu.Ink,
                            unselectedIconColor = Sgu.Sub, unselectedTextColor = Sgu.Sub,
                            indicatorColor = Sgu.Bubble,
                        ),
                    )
                }
            }
            VerticalDivider(color = Sgu.Line)
            Box(Modifier.weight(1f)) { content() }
        }
    } else {
        Scaffold(
            containerColor = Sgu.Bg,
            bottomBar = {
                Column {
                    HorizontalDivider(color = Sgu.Line)
                    NavigationBar(containerColor = Sgu.Bg) {
                        tabs.forEachIndexed { i, t ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Icon(if (tab == i) t.selectedIcon else t.icon, contentDescription = t.label) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Sgu.Ink, selectedTextColor = Sgu.Ink,
                                    unselectedIconColor = Sgu.Sub, unselectedTextColor = Sgu.Sub,
                                    indicatorColor = Sgu.Bubble,
                                ),
                            )
                        }
                    }
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) { content() }
        }
    }
}
