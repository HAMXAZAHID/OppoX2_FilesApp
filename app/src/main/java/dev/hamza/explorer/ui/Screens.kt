package dev.hamza.explorer.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.hamza.explorer.R
import dev.hamza.explorer.core.FileKind
import dev.hamza.explorer.core.FileOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val PKG = "dev.hamza.explorer"

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

// ---------------- Home: storage and common folders ----------------

@Composable
fun HomeScreen(vm: ExplorerViewModel, onFolder: (File) -> Unit, onAccess: () -> Unit, onSettings: () -> Unit) {
    val roots = remember(vm.version) { vm.roots() }
    val quick = remember(vm.version) { vm.quickFolders() }
    ListScreen {
        header("Explorer")
        if (!vm.fullAccess) {
            item {
                ActionChip(
                    "Allow file access", R.drawable.ic_lock,
                    secondary = if (vm.readAccess) "Needed to change files" else "Needed to see your files",
                    colors = ChipDefaults.primaryChipColors(),
                    onClick = onAccess,
                )
            }
        }
        vm.clip?.let { c ->
            note("${if (c.cut) "Moving" else "Copying"} ${c.file.name}: open a folder and tap Paste here.")
        }
        item {
            ActionChip(
                "Downloads", R.drawable.ic_folder,
                secondary = "Home Files saves here",
                colors = ChipDefaults.primaryChipColors(),
            ) { vm.downloads.mkdirs(); onFolder(vm.downloads) }
        }
        roots.forEach { r ->
            item {
                ActionChip(
                    r.label, R.drawable.ic_storage,
                    secondary = "${formatSize(r.dir.usableSpace)} free of ${formatSize(r.dir.totalSpace)}",
                    colors = ChipDefaults.primaryChipColors(),
                ) { onFolder(r.dir) }
            }
        }
        quick.forEach { f ->
            item { ActionChip(f.name, R.drawable.ic_folder) { onFolder(f) } }
        }
        item { ActionChip("Settings", R.drawable.ic_settings, secondary = "Sort: ${vm.sort.label}", onClick = onSettings) }
    }
}

// ---------------- Storage permission ----------------

@Composable
fun AccessScreen(vm: ExplorerViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val readLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshAccess() }
    LaunchedEffect(vm.fullAccess) { if (vm.fullAccess) onDone() }
    ListScreen {
        header("File access")
        note("Explorer needs \"All files access\" to show and change files on the watch.")
        item {
            ActionChip("Open setting", R.drawable.ic_lock, colors = ChipDefaults.primaryChipColors()) {
                if (!openAllFilesSetting(context)) toast(context, "This watch has no screen for it. Use the PC command below.")
            }
        }
        if (!vm.readAccess) {
            item {
                ActionChip("Read-only access", R.drawable.ic_hidden, secondary = "Photos, music, videos") {
                    readLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
        note("Or from a PC with adb:\nadb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow")
        item { ActionChip("Check again", R.drawable.ic_refresh) { vm.refreshAccess() } }
    }
}

private fun openAllFilesSetting(context: Context): Boolean {
    val intents = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$PKG")),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$PKG")),
    )
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return false
}

// ---------------- Folder browser ----------------

@Composable
fun BrowseScreen(
    vm: ExplorerViewModel,
    dir: File,
    onFolder: (File) -> Unit,
    onOpen: (File) -> Unit,
    onOptions: (File) -> Unit,
    onNewFolder: () -> Unit,
    onOperation: () -> Unit,
) {
    LaunchedEffect(dir, vm.version, vm.sort, vm.showHidden) { vm.load(dir) }
    val listing = vm.listings[dir.path]
    val clip = vm.clip

    ListScreen {
        header(dir.name.ifEmpty { "/" })
        note(vm.displayPath(dir))
        if (clip != null) {
            val blocked = clip.file.isDirectory && FileOps.isSameOrInside(dir, clip.file)
            item {
                ActionChip(
                    "Paste here", R.drawable.ic_paste,
                    secondary = (if (clip.cut) "Move " else "Copy ") + clip.file.name,
                    colors = ChipDefaults.primaryChipColors(),
                    enabled = !blocked,
                ) { vm.paste(dir); onOperation() }
            }
            item { ActionChip("Cancel ${if (clip.cut) "move" else "copy"}", R.drawable.ic_close) { vm.clip = null } }
        }
        when (listing) {
            null, Listing.Loading -> item { CircularProgressIndicator(modifier = Modifier.size(36.dp)) }
            is Listing.Failed -> {
                note(listing.message, ErrorColor)
                if (!vm.readAccess) note("Allow file access from the first screen.")
                item { ActionChip("Try again", R.drawable.ic_refresh) { vm.load(dir, force = true) } }
            }
            is Listing.Loaded -> {
                if (listing.entries.isEmpty()) note("This folder is empty.")
                listing.entries.forEach { e ->
                    item(key = e.file.path) {
                        ItemRow(
                            name = e.name,
                            kind = e.kind,
                            secondary = if (e.isDir) {
                                if (e.childCount < 0) "Can't open" else "${e.childCount} item${if (e.childCount == 1) "" else "s"}"
                            } else if (e.name.endsWith(".part")) {
                                // Home Files writes downloads as name.part and renames them when done.
                                "Still downloading · " + formatSize(e.size)
                            } else {
                                formatSize(e.size) + " · " + formatDate(e.modified)
                            },
                            onClick = { if (e.isDir) onFolder(e.file) else onOpen(e.file) },
                            onLongClick = { onOptions(e.file) },
                        )
                    }
                }
                if (listing.entries.isNotEmpty()) note("Long-press a file or folder for options.")
            }
        }
        item { ActionChip("New folder", R.drawable.ic_new_folder, enabled = vm.fullAccess, onClick = onNewFolder) }
        item { ActionChip("Sort: ${vm.sort.label}", R.drawable.ic_sort) { vm.cycleSort() } }
        if (!vm.isRoot(dir)) {
            item { ActionChip("This folder…", R.drawable.ic_info) { onOptions(dir) } }
        }
        item { ActionChip("Refresh", R.drawable.ic_refresh) { vm.load(dir, force = true) } }
    }
}

// ---------------- Options for one file or folder ----------------

@Composable
fun OptionsScreen(
    vm: ExplorerViewModel,
    onOpen: (File) -> Unit,
    onOpenFolder: (File) -> Unit,
    onRename: () -> Unit,
    onClipped: () -> Unit,
    onOperation: () -> Unit,
) {
    val file = vm.selected ?: return
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val isDir = remember(file) { file.isDirectory }
    val stats by produceState<String?>(null, file, vm.version) {
        value = withContext(Dispatchers.IO) {
            if (isDir) FileOps.stats(file).let { "${it.files} files, ${it.folders} folders · ${formatSize(it.bytes)}" }
            else "${formatSize(file.length())} · ${formatDate(file.lastModified())}"
        }
    }
    val canWrite = vm.fullAccess

    ListScreen {
        header(file.name)
        note(stats ?: "…")
        if (isDir) {
            item { ActionChip("Open folder", R.drawable.ic_folder, colors = ChipDefaults.primaryChipColors()) { onOpenFolder(file) } }
        } else {
            item { ActionChip("Open", R.drawable.ic_open, colors = ChipDefaults.primaryChipColors()) { onOpen(file) } }
            item {
                ActionChip("Open with another app", R.drawable.ic_open) {
                    try {
                        context.startActivity(Intent.createChooser(vm.openWithIntent(file), file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                        toast(context, "No app on the watch can open this file.")
                    }
                }
            }
        }
        item {
            ActionChip("Copy", R.drawable.ic_copy, secondary = "Then pick where") {
                vm.clip = Clip(file, cut = false); onClipped()
            }
        }
        item {
            ActionChip("Move", R.drawable.ic_move, secondary = "Then pick where", enabled = canWrite) {
                vm.clip = Clip(file, cut = true); onClipped()
            }
        }
        item { ActionChip("Rename", R.drawable.ic_edit, enabled = canWrite, onClick = onRename) }
        item {
            ActionChip(
                if (confirmDelete) "Tap again to delete" else "Delete",
                R.drawable.ic_delete,
                secondary = if (confirmDelete && isDir) "Deletes everything inside" else null,
                enabled = canWrite,
            ) {
                if (confirmDelete) { vm.delete(file); onOperation() } else confirmDelete = true
            }
        }
        note(vm.displayPath(file))
    }
}

// ---------------- Rename and new folder ----------------

@Composable
fun NameScreen(title: String, initial: String, action: String, onSave: (String, (String?) -> Unit) -> Unit, onDone: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val save = {
        if (!busy) {
            error = FileOps.nameProblem(name)
            if (error == null) {
                busy = true
                onSave(name) { err -> busy = false; if (err == null) onDone() else error = err }
            }
        }
    }
    ListScreen {
        header(title)
        item { Field("Name", name, { name = it; error = null }, onDone = save) }
        error?.let { note(it, ErrorColor) }
        item {
            ActionChip(action, R.drawable.ic_check, colors = ChipDefaults.primaryChipColors(), enabled = name.isNotBlank() && !busy) { save() }
        }
    }
}

// ---------------- Settings ----------------

@Composable
fun SettingsScreen(vm: ExplorerViewModel, onAccess: () -> Unit) {
    ListScreen {
        header("Settings")
        item { ActionChip("Sort by", R.drawable.ic_sort, secondary = vm.sort.label) { vm.cycleSort() } }
        item {
            ActionChip("Hidden files", R.drawable.ic_hidden, secondary = if (vm.showHidden) "Shown" else "Hidden") { vm.toggleHidden() }
        }
        item {
            ActionChip(
                "File access", R.drawable.ic_lock,
                secondary = when {
                    vm.fullAccess -> "All files"
                    vm.readAccess -> "Read-only"
                    else -> "Not allowed"
                },
                onClick = onAccess,
            )
        }
        note("Explorer 0.1.1 · works offline, no Google services needed.")
    }
}

// ---------------- Copy / move / delete progress ----------------

@Composable
fun OperationScreen(vm: ExplorerViewModel, onClose: () -> Unit) {
    val op = vm.operation
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    if (op == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = if (op.finished && op.error == null) 1f else op.fraction,
            modifier = Modifier.fillMaxSize().padding(4.dp),
            strokeWidth = 6.dp,
            indicatorColor = if (op.error != null) ErrorColor else MaterialTheme.colors.primary,
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
        ) {
            Text(if (op.error != null) "${op.title} failed" else op.title, style = MaterialTheme.typography.title3)
            Text(op.name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2)
            Text(
                op.error ?: if (op.total > 0) "${formatSize(op.done)} / ${formatSize(op.total)}" else "",
                color = if (op.error != null) ErrorColor else Muted,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.caption2,
                maxLines = 3,
            )
            Row(Modifier.padding(top = 8.dp)) {
                if (op.finished) {
                    Button(onClick = { vm.clearOperation(); onClose() }, modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = "Done")
                    }
                } else {
                    Button(
                        onClick = { vm.cancelOperation() },
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                    ) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Stop")
                    }
                }
            }
        }
    }
}

/** What tapping a file does: built-in viewers for common types, another app for the rest. */
fun openRoute(kind: FileKind): String? = when (kind) {
    FileKind.IMAGE -> "image"
    FileKind.TEXT -> "text"
    FileKind.WEB -> "web"
    FileKind.AUDIO -> "audio"
    FileKind.VIDEO -> "video"
    else -> null
}
