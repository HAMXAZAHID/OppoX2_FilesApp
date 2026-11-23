package dev.hamza.explorer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.hamza.explorer.core.FileKind
import dev.hamza.explorer.core.FileOps
import dev.hamza.explorer.ui.AccessScreen
import dev.hamza.explorer.ui.AudioPlayer
import dev.hamza.explorer.ui.BrowseScreen
import dev.hamza.explorer.ui.ExplorerViewModel
import dev.hamza.explorer.ui.HomeScreen
import dev.hamza.explorer.ui.ImageViewer
import dev.hamza.explorer.ui.NameScreen
import dev.hamza.explorer.ui.OperationScreen
import dev.hamza.explorer.ui.OptionsScreen
import dev.hamza.explorer.ui.SettingsScreen
import dev.hamza.explorer.ui.TextViewer
import dev.hamza.explorer.ui.VideoPlayer
import dev.hamza.explorer.ui.WebViewer
import dev.hamza.explorer.ui.openRoute
import dev.hamza.explorer.ui.toast
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: ExplorerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App(vm) } }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the "All files access" setting.
        vm.refreshAccess()
    }
}

private fun browseRoute(dir: File) = "browse?path=" + Uri.encode(dir.absolutePath)
private fun newFolderRoute(dir: File) = "newfolder?path=" + Uri.encode(dir.absolutePath)

@Composable
private fun App(vm: ExplorerViewModel) {
    val nav = rememberSwipeDismissableNavController()
    val context = LocalContext.current
    val pathArg = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })

    val open: (File) -> Unit = { file ->
        val kind = FileOps.kindOf(file.name)
        val route = openRoute(kind)
        when {
            route != null -> { vm.viewing = file; nav.navigate(route) }
            kind == FileKind.APK -> try {
                context.startActivity(vm.installIntent(file))
            } catch (_: ActivityNotFoundException) {
                toast(context, "This watch can't install apps this way. Use adb install.")
            }
            else -> try {
                context.startActivity(Intent.createChooser(vm.openWithIntent(file), file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                toast(context, "No app on the watch can open this file.")
            }
        }
    }
    val options: (File) -> Unit = { vm.selected = it; nav.navigate("options") }

    val current by nav.currentBackStackEntryAsState()
    // Web pages need every swipe for themselves; they have a close button instead.
    SwipeDismissableNavHost(
        navController = nav,
        startDestination = "home",
        userSwipeEnabled = current?.destination?.route != "web",
    ) {
        composable("home") {
            HomeScreen(
                vm,
                onFolder = { nav.navigate(browseRoute(it)) },
                onAccess = { nav.navigate("access") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("access") { AccessScreen(vm, onDone = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(vm, onAccess = { nav.navigate("access") }) }
        composable("browse?path={path}", arguments = pathArg) { entry ->
            val dir = File(entry.arguments?.getString("path").orEmpty().ifEmpty { vm.primary.absolutePath })
            BrowseScreen(
                vm, dir,
                onFolder = { nav.navigate(browseRoute(it)) },
                onOpen = open,
                onOptions = options,
                onNewFolder = { nav.navigate(newFolderRoute(dir)) },
                onOperation = { nav.navigate("operation") },
            )
        }
        composable("options") {
            OptionsScreen(
                vm,
                onOpen = open,
                onOpenFolder = { nav.navigate(browseRoute(it)) { popUpTo("options") { inclusive = true } } },
                onRename = { nav.navigate("rename") },
                onClipped = {
                    toast(context, "Now open a folder and tap Paste here.")
                    nav.popBackStack()
                },
                onOperation = { nav.navigate("operation") { popUpTo("options") { inclusive = true } } },
            )
        }
        composable("rename") {
            val file = vm.selected
            if (file != null) {
                NameScreen(
                    "Rename", file.name, "Save",
                    onSave = { name, done -> vm.rename(file, name, done) },
                    onDone = { nav.popBackStack("options", inclusive = true) },
                )
            }
        }
        composable("newfolder?path={path}", arguments = pathArg) { entry ->
            val parent = File(entry.arguments?.getString("path").orEmpty())
            NameScreen(
                "New folder", "", "Create",
                onSave = { name, done -> vm.createFolder(parent, name, done) },
                onDone = { nav.popBackStack() },
            )
        }
        composable("operation") { OperationScreen(vm, onClose = { nav.popBackStack() }) }
        composable("image") { ImageViewer(vm) }
        composable("text") { TextViewer(vm) }
        composable("audio") { AudioPlayer(vm) }
        composable("video") { VideoPlayer(vm) }
        composable("web") {
            WebViewer(
                vm,
                onClose = { nav.popBackStack() },
                onOpenAsText = { nav.navigate("text") { popUpTo("web") { inclusive = true } } },
                onOpenWith = {
                    vm.viewing?.let { f ->
                        try {
                            context.startActivity(Intent.createChooser(vm.openWithIntent(f), f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: ActivityNotFoundException) {
                            toast(context, "No other app on the watch can open this file.")
                        }
                    }
                },
            )
        }
    }
}
