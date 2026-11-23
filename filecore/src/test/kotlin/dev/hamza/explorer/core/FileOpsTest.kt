package dev.hamza.explorer.core

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(dir: File, name: String, body: String = name) = File(dir, name).apply { parentFile.mkdirs(); writeText(body) }

    @Test fun listsFoldersFirstAndHidesDotFiles() {
        val d = tmp.newFolder("d")
        file(d, "b.txt"); file(d, "A.txt"); file(d, ".hidden")
        File(d, "zdir").mkdir()
        assertEquals(listOf("zdir", "A.txt", "b.txt"), FileOps.list(d).map { it.name })
        assertEquals(4, FileOps.list(d, showHidden = true).size)
    }

    @Test fun sortsBySize() {
        val d = tmp.newFolder("d")
        file(d, "small", "x"); file(d, "big", "x".repeat(100))
        assertEquals(listOf("big", "small"), FileOps.list(d, SortOrder.SIZE).map { it.name })
    }

    @Test fun listingMissingFolderFails() {
        assertFailsWith<IOException> { FileOps.list(File(tmp.root, "nope")) }
    }

    @Test fun kinds() {
        assertEquals(FileKind.IMAGE, FileOps.kindOf("a.JPG"))
        assertEquals(FileKind.TEXT, FileOps.kindOf("notes.txt"))
        assertEquals(FileKind.AUDIO, FileOps.kindOf("song.mp3"))
        assertEquals(FileKind.VIDEO, FileOps.kindOf("clip.mp4"))
        assertEquals(FileKind.APK, FileOps.kindOf("app.apk"))
        assertEquals(FileKind.OTHER, FileOps.kindOf("README"))
    }

    @Test fun numberedNames() {
        assertEquals("a (1).txt", FileOps.numbered("a.txt", 1, false))
        assertEquals("a.b (2)", FileOps.numbered("a.b", 2, true))
        assertEquals(".rc (1)", FileOps.numbered(".rc", 1, false))
    }

    @Test fun copyFileKeepsBothOnClash() {
        val src = tmp.newFolder("src"); val dst = tmp.newFolder("dst")
        val f = file(src, "a.txt", "hello")
        file(dst, "a.txt", "old")
        val out = FileOps.copy(f, dst)
        assertEquals("a (1).txt", out.name)
        assertEquals("hello", out.readText())
        assertEquals("old", File(dst, "a.txt").readText())
        assertTrue(f.exists())
    }

    @Test fun copyFolderRecursivelyWithProgress() {
        val src = tmp.newFolder("src"); val dst = tmp.newFolder("dst")
        file(src, "x/one.txt", "1"); file(src, "x/sub/two.txt", "22")
        var last = 0L
        val out = FileOps.copy(File(src, "x"), dst, { done, total, _ -> last = done; assertEquals(3, total) })
        assertEquals(3, last)
        assertEquals("22", File(out, "sub/two.txt").readText())
    }

    @Test fun copyIntoSameFolderMakesNumberedCopy() {
        val d = tmp.newFolder("d")
        val f = file(d, "a.txt")
        assertEquals("a (1).txt", FileOps.copy(f, d).name)
    }

    @Test fun cannotCopyOrMoveFolderIntoItself() {
        val d = tmp.newFolder("d")
        val inner = File(d, "in").apply { mkdirs() }
        assertFailsWith<IOException> { FileOps.copy(d, inner) }
        assertFailsWith<IOException> { FileOps.move(d, inner) }
        assertFailsWith<IOException> { FileOps.copy(d, d) }
    }

    @Test fun cancelledCopyLeavesNothingBehind() {
        val src = tmp.newFolder("src"); val dst = tmp.newFolder("dst")
        file(src, "x/a.txt"); file(src, "x/b.txt")
        var calls = 0
        assertFailsWith<CancelledException> { FileOps.copy(File(src, "x"), dst, isCancelled = { ++calls > 2 }) }
        assertFalse(File(dst, "x").exists())
    }

    @Test fun moveFolder() {
        val src = tmp.newFolder("src"); val dst = tmp.newFolder("dst")
        file(src, "x/a.txt", "a")
        val out = FileOps.move(File(src, "x"), dst)
        assertEquals("a", File(out, "a.txt").readText())
        assertFalse(File(src, "x").exists())
    }

    @Test fun moveToSameFolderIsRejected() {
        val d = tmp.newFolder("d")
        assertFailsWith<IOException> { FileOps.move(file(d, "a"), d) }
    }

    @Test fun renameRules() {
        val d = tmp.newFolder("d")
        val a = file(d, "a.txt"); file(d, "b.txt")
        assertFailsWith<IOException> { FileOps.rename(a, "b.txt") }
        assertFailsWith<IOException> { FileOps.rename(a, "x/y") }
        assertFailsWith<IOException> { FileOps.rename(a, "  ") }
        val c = FileOps.rename(a, " c.txt ")
        assertEquals("c.txt", c.name)
        assertTrue(c.exists()); assertFalse(a.exists())
        assertNull(FileOps.nameProblem("ok name.txt"))
        assertNotNull(FileOps.nameProblem(".."))
    }

    @Test fun createAndDeleteFolder() {
        val d = tmp.newFolder("d")
        val f = FileOps.createFolder(d, "New")
        file(f, "deep/er/file.txt")
        assertFailsWith<IOException> { FileOps.createFolder(d, "New") }
        assertEquals(FolderStats(1, 2, 8), FileOps.stats(f))
        FileOps.delete(f)
        assertFalse(f.exists())
    }

    @Test fun readsTextAndFlagsBinary() {
        val d = tmp.newFolder("d")
        val t = FileOps.readText(file(d, "a.txt", "héllo"))
        assertEquals("héllo", t.text); assertFalse(t.truncated); assertFalse(t.looksBinary)
        val big = File(d, "big.txt").apply { writeText("x".repeat(100)) }
        assertTrue(FileOps.readText(big, maxBytes = 10).truncated)
        val bin = File(d, "b.bin").apply { writeBytes(byteArrayOf(1, 0, 2)) }
        assertTrue(FileOps.readText(bin).looksBinary)
        assertContentEquals(byteArrayOf(1, 0, 2), bin.readBytes())
    }
}
