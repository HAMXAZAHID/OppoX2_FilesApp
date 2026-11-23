package dev.hamza.explorer.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hamza.explorer.core.CancelledException
import dev.hamza.explorer.core.Entry
import dev.hamza.explorer.core.FileOps
import dev.hamza.explorer.core.ProgressListener
import dev.hamza.explorer.core.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Listing {
    data object Loading : Listing
    data class Loaded(val entries: List<Entry>, val key: String) : Listing
    data class Failed(val message: String, val key: String) : Listing
}

/** A file or folder picked with Copy or Move, waiting for "Paste here". */
data class Clip(val file: File, val cut: Boolean)

/** A running or finished copy, move or delete. */
data class Operation(
    val title: String,
    val name: String,
    val done: Long = 0,
    val total: Long = 0,
    val finished: Boolean = false,
    val error: String? = null,
    val result: String? = null,
) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

data class StorageRoot(val label: String, val dir: File)

class ExplorerViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sort by mutableStateOf(runCatching { SortOrder.valueOf(prefs.getString("sort", null) ?: "") }.getOrDefault(SortOrder.NAME))
        private set
    var showHidden by mutableStateOf(prefs.getBoolean("hidden", false))
        private set

    /** Bumped after anything changes on storage, so open folders reload. */
    var version by mutableIntStateOf(0)
        private set

    val listings = mutableStateMapOf<String, Listing>()

    /** The file or folder the options screen is about. */
    var selected by mutableStateOf<File?>(null)

    /** The file shown by a viewer. */
    var viewing by mutableStateOf<File?>(null)

    var clip by mutableStateOf<Clip?>(null)
    var operation by mutableStateOf<Operation?>(null)
        private set
    private var opJob: Job? = null
    @Volatile private var cancelRequested = false

    var fullAccess by mutableStateOf(false)
        private set
    var readAccess by mutableStateOf(false)
        private set

    init {
        refreshAccess()
    }


    fun refreshAccess() {
        val ctx = getApplication<Application>()
        fullAccess = Environment.isExternalStorageManager()
        readAccess = fullAccess ||
            ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        version++
    }

    val primary: File get() = Environment.getExternalStorageDirectory()

    fun roots(): List<StorageRoot> {
        val sm = getApplication<Application>().getSystemService(StorageManager::class.java)
        val out = mutableListOf(StorageRoot("Watch storage", primary))
        sm?.storageVolumes?.forEach { v ->
            val dir = v.directory ?: return@forEach
            if (!v.isPrimary && dir.canRead()) out += StorageRoot(v.getDescription(getApplication()) ?: dir.name, dir)
        }
        return out
    }

    /** The shared Download folder, where Home Files saves what it downloads. */
    val downloads: File get() = File(primary, Environment.DIRECTORY_DOWNLOADS)

    /** Other common folders that exist on this watch. */
    fun quickFolders(): List<File> = listOf(
        Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_PICTURES,
        Environment.DIRECTORY_DCIM, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_DOCUMENTS,
        "Recordings", // Environment.DIRECTORY_RECORDINGS only exists from Android 12.
    ).map { File(primary, it) }.filter { it.isDirectory }

    /** Shows paths the way people know them: "/sdcard/Music" instead of "/storage/emulated/0/Music". */
    fun displayPath(f: File): String {
        val p = primary.absolutePath
        return when {
            f.absolutePath == p -> "/sdcard"
            f.absolutePath.startsWith("$p/") -> "/sdcard" + f.absolutePath.removePrefix(p)
            else -> f.absolutePath
        }
    }

    fun isRoot(dir: File): Boolean = roots().any { it.dir.absolutePath == dir.absolutePath }

    private fun listingKey() = "$version/$sort/$showHidden"

    fun load(dir: File, force: Boolean = false) {
        val key = listingKey()
        val current = listings[dir.path]
        val currentKey = (current as? Listing.Loaded)?.key ?: (current as? Listing.Failed)?.key
        if (!force && currentKey == key) return
        if (current == null) listings[dir.path] = Listing.Loading
        val sortNow = sort
        val hiddenNow = showHidden
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { FileOps.list(dir, sortNow, hiddenNow) } }
            listings[dir.path] = result.fold(
                { Listing.Loaded(it, key) },
                { Listing.Failed(it.message ?: "Can't open this folder.", key) },
            )
        }
    }

    fun cycleSort() {
        sort = SortOrder.entries[(sort.ordinal + 1) % SortOrder.entries.size]
        prefs.edit().putString("sort", sort.name).apply()
    }

    fun toggleHidden() {
        showHidden = !showHidden
        prefs.edit().putBoolean("hidden", showHidden).apply()
    }

    private fun changed() { version++ }

    // ---------- Quick actions (rename, new folder) ----------

    fun rename(file: File, newName: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FileOps.rename(file, newName) } }
            r.onSuccess { renamed ->
                if (selected == file) selected = renamed
                clip?.let { c -> if (c.file == file) clip = c.copy(file = renamed) }
                changed()
            }
            done(r.exceptionOrNull()?.message)
        }
    }

    fun createFolder(parent: File, name: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FileOps.createFolder(parent, name) } }
            if (r.isSuccess) changed()
            done(r.exceptionOrNull()?.message)
        }
    }

    // ---------- Long operations (copy, move, delete) with progress ----------

    fun paste(destDir: File) {
        val c = clip ?: return
        val verb = if (c.cut) "Moving" else "Copying"
        run("$verb", c.file.name, if (c.cut) "Moved" else "Copied") { listener, cancelled ->
            if (c.cut) FileOps.move(c.file, destDir, listener, cancelled) else FileOps.copy(c.file, destDir, listener, cancelled)
            clip = null
        }
    }

    fun delete(file: File) {
        run("Deleting", file.name, "Deleted") { _, _ ->
            FileOps.delete(file)
            if (clip?.file == file) clip = null
        }
    }

    private fun run(title: String, name: String, doneTitle: String, block: (ProgressListener, () -> Boolean) -> Unit) {
        if (opJob?.isActive == true) return
        cancelRequested = false
        operation = Operation(title, name)
        opJob = viewModelScope.launch {
            var last = 0L
            val listener = ProgressListener { done, total, current ->
                val now = System.currentTimeMillis()
                if (now - last > 150 || done == total) {
                    last = now
                    viewModelScope.launch { operation = operation?.copy(done = done, total = total, name = current) }
                }
            }
            val r = withContext(Dispatchers.IO) { runCatching { block(listener) { cancelRequested } } }
            changed()
            operation = operation?.copy(
                finished = true,
                name = name,
                title = if (r.isSuccess) doneTitle else title,
                error = r.exceptionOrNull()?.let { if (it is CancelledException) "Stopped" else it.message ?: "Something went wrong." },
            )
        }
    }

    fun cancelOperation() { cancelRequested = true }

    fun clearOperation() { operation = null }

    // ---------- Opening with other apps ----------

    fun uriFor(file: File): Uri {
        val ctx = getApplication<Application>()
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    }

    fun mimeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

    fun openWithIntent(file: File): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(file), mimeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(file: File): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}
