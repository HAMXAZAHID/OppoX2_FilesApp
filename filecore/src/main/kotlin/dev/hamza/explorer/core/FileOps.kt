package dev.hamza.explorer.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

enum class SortOrder(val label: String) { NAME("Name"), DATE("Newest"), SIZE("Largest") }

enum class FileKind { FOLDER, IMAGE, TEXT, AUDIO, VIDEO, APK, OTHER }

/** One row in a folder listing. [childCount] is -1 when a folder can't be read. */
data class Entry(
    val file: File,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val childCount: Int,
) {
    val name: String get() = file.name
    val kind: FileKind get() = if (isDir) FileKind.FOLDER else FileOps.kindOf(file.name)
}

fun interface ProgressListener {
    fun onProgress(done: Long, total: Long, current: String)
}

class CancelledException : IOException("Stopped")

data class FolderStats(val files: Int, val folders: Int, val bytes: Long)

data class TextPreview(val text: String, val truncated: Boolean, val looksBinary: Boolean)

object FileOps {
    private const val BUFFER = 64 * 1024

    private val image = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    private val text = setOf(
        "txt", "md", "log", "json", "xml", "csv", "tsv", "ini", "conf", "cfg", "properties", "yaml", "yml",
        "toml", "css", "js", "ts", "kt", "kts", "java", "py", "sh", "bat", "c", "h", "cpp",
        "srt", "lrc", "nfo", "gradle", "sql", "rc", "prop",
    )
    private val audio = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "amr", "mid", "midi", "wma")
    private val video = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi", "ts")

    fun kindOf(name: String): FileKind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in image -> FileKind.IMAGE
            "ts" -> FileKind.VIDEO
            in text -> FileKind.TEXT
            in audio -> FileKind.AUDIO
            in video -> FileKind.VIDEO
            "apk" -> FileKind.APK
            else -> FileKind.OTHER
        }
    }

    /** Folders first, then files, in the chosen order. */
    fun list(dir: File, sort: SortOrder = SortOrder.NAME, showHidden: Boolean = false): List<Entry> {
        val files = dir.listFiles() ?: throw IOException(
            if (!dir.exists()) "This folder no longer exists." else "Can't open this folder."
        )
        val entries = files.asSequence()
            .filter { showHidden || !it.name.startsWith(".") }
            .map { f ->
                val d = f.isDirectory
                Entry(f, d, if (d) 0L else f.length(), f.lastModified(), if (d) f.list()?.size ?: -1 else 0)
            }
            .toList()
        val byName = compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val order: Comparator<Entry> = when (sort) {
            SortOrder.NAME -> byName
            SortOrder.DATE -> compareByDescending<Entry> { it.modified }.then(byName)
            SortOrder.SIZE -> compareByDescending<Entry> { it.size }.then(byName)
        }
        return entries.sortedWith(compareBy<Entry> { !it.isDir }.then(order))
    }

    /** Null when [name] is fine as a file or folder name, else why not. */
    fun nameProblem(name: String): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "Enter a name."
            n == "." || n == ".." -> "That name isn't allowed."
            n.contains('/') || n.contains('\u0000') -> "Names can't contain /"
            n.toByteArray().size > 255 -> "That name is too long."
            else -> null
        }
    }

    /** "name (n).ext" for files, "name (n)" for folders. */
    fun numbered(name: String, n: Int, isDir: Boolean): String {
        val dot = name.lastIndexOf('.')
        return if (isDir || dot <= 0) "$name ($n)" else "${name.substring(0, dot)} ($n)${name.substring(dot)}"
    }

    /** A path in [dir] for [name] that doesn't overwrite anything. */
    fun uniqueTarget(dir: File, name: String, isDir: Boolean): File {
        var f = File(dir, name)
        var n = 1
        while (f.exists() || Files.isSymbolicLink(f.toPath())) f = File(dir, numbered(name, n++, isDir))
        return f
    }

    /** True if [child] is [parent] or somewhere inside it. */
    fun isSameOrInside(child: File, parent: File): Boolean {
        val p = parent.canonicalFile
        var c: File? = child.canonicalFile
        while (c != null) {
            if (c == p) return true
            c = c.parentFile
        }
        return false
    }

    private fun isLink(f: File) = Files.isSymbolicLink(f.toPath())

    fun totalSize(f: File): Long =
        if (f.isDirectory && !isLink(f)) f.listFiles()?.sumOf { totalSize(it) } ?: 0L else f.length()

    fun stats(dir: File): FolderStats {
        var files = 0
        var folders = 0
        var bytes = 0L
        fun walk(d: File) {
            d.listFiles()?.forEach {
                if (it.isDirectory && !isLink(it)) { folders++; walk(it) } else { files++; bytes += it.length() }
            }
        }
        walk(dir)
        return FolderStats(files, folders, bytes)
    }

    /** Copies [src] into [destDir], picking a new name on a clash. Returns the new file or folder. */
    fun copy(src: File, destDir: File, listener: ProgressListener? = null, isCancelled: () -> Boolean = { false }): File {
        checkSource(src, destDir, "copy")
        val target = uniqueTarget(destDir, src.name, src.isDirectory)
        val total = totalSize(src)
        var done = 0L
        try {
            copyInto(src, target, isCancelled) { n, name ->
                done += n
                listener?.onProgress(done, total, name)
            }
        } catch (e: Exception) {
            deleteRecursive(target)
            throw e
        }
        listener?.onProgress(total, total, src.name)
        return target
    }

    /** Moves [src] into [destDir]: a quick rename on the same storage, otherwise copy then delete. */
    fun move(src: File, destDir: File, listener: ProgressListener? = null, isCancelled: () -> Boolean = { false }): File {
        checkSource(src, destDir, "move")
        if (src.parentFile?.canonicalFile == destDir.canonicalFile) throw IOException("It's already in this folder.")
        val target = uniqueTarget(destDir, src.name, src.isDirectory)
        if (src.renameTo(target)) {
            val size = totalSize(target)
            listener?.onProgress(size, size, src.name)
            return target
        }
        val copied = copy(src, destDir, listener, isCancelled)
        if (!deleteRecursive(src)) throw IOException("Copied, but couldn't remove the original.")
        return copied
    }

    private fun checkSource(src: File, destDir: File, verb: String) {
        if (!src.exists()) throw IOException("${src.name} no longer exists.")
        if (!destDir.isDirectory) throw IOException("The destination folder is missing.")
        if (src.isDirectory && isSameOrInside(destDir, src)) throw IOException("Can't $verb a folder into itself.")
    }

    private fun copyInto(src: File, target: File, isCancelled: () -> Boolean, progress: (Long, String) -> Unit) {
        if (isCancelled()) throw CancelledException()
        if (src.isDirectory && !isLink(src)) {
            if (!target.mkdir()) throw IOException("Couldn't create ${target.name}.")
            val children = src.listFiles() ?: throw IOException("Can't read ${src.name}.")
            for (c in children) copyInto(c, File(target, c.name), isCancelled, progress)
        } else {
            FileInputStream(src).use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        if (isCancelled()) throw CancelledException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        progress(n.toLong(), src.name)
                    }
                    out.fd.sync()
                }
            }
        }
        target.setLastModified(src.lastModified())
    }

    /** Deletes a file, or a folder with everything in it. Doesn't follow links. True when it's gone. */
    fun deleteRecursive(f: File): Boolean {
        if (f.isDirectory && !isLink(f)) f.listFiles()?.forEach { deleteRecursive(it) }
        return f.delete() || !f.exists()
    }

    fun delete(f: File) {
        if (!f.exists()) return
        if (!deleteRecursive(f)) throw IOException("Couldn't delete everything in ${f.name}.")
    }

    fun rename(f: File, newName: String): File {
        nameProblem(newName)?.let { throw IOException(it) }
        val name = newName.trim()
        if (name == f.name) return f
        val target = File(f.parentFile, name)
        // Allow changing only the letter case, which some storage reports as "exists".
        if (target.exists() && !name.equals(f.name, ignoreCase = true)) throw IOException("$name already exists.")
        if (!f.renameTo(target)) throw IOException("Couldn't rename ${f.name}.")
        return target
    }

    fun createFolder(parent: File, name: String): File {
        nameProblem(name)?.let { throw IOException(it) }
        val dir = File(parent, name.trim())
        if (dir.exists()) throw IOException("${dir.name} already exists.")
        if (!dir.mkdir()) throw IOException("Couldn't create ${dir.name}.")
        return dir
    }

    /** Reads up to [maxBytes] of a file as UTF-8 text. */
    fun readText(f: File, maxBytes: Int = 256 * 1024): TextPreview {
        val len = f.length()
        val bytes = FileInputStream(f).use { input ->
            val want = minOf(len, maxBytes.toLong()).toInt()
            val buf = ByteArray(want)
            var off = 0
            while (off < want) {
                val n = input.read(buf, off, want - off)
                if (n < 0) break
                off += n
            }
            buf.copyOf(off)
        }
        val sample = bytes.take(4096)
        val binary = sample.count { it == 0.toByte() } > 0
        return TextPreview(String(bytes, Charsets.UTF_8), len > bytes.size, binary)
    }
}
