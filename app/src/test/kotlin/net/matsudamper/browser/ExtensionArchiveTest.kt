package net.matsudamper.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionArchiveTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `manifest_json を含む ZIP は拡張機能と判定される`() {
        val file = createZip("extension.zip", listOf("manifest.json", "background.js"))
        assertTrue(isWebExtensionArchive(file))
    }

    @Test
    fun `拡張子が xpi でも中身で判定される`() {
        val file = createZip("extension.xpi", listOf("manifest.json"))
        assertTrue(isWebExtensionArchive(file))
    }

    @Test
    fun `manifest_json を含まない ZIP は拡張機能ではない`() {
        val file = createZip("archive.zip", listOf("readme.txt"))
        assertFalse(isWebExtensionArchive(file))
    }

    @Test
    fun `ルート以外の manifest_json は拡張機能ではない`() {
        val file = createZip("nested.zip", listOf("extension/manifest.json"))
        assertFalse(isWebExtensionArchive(file))
    }

    @Test
    fun `ZIP ではないファイルは拡張機能ではない`() {
        val file = temporaryFolder.newFile("not-zip.zip")
        file.writeText("これは ZIP ではありません")
        assertFalse(isWebExtensionArchive(file))
    }

    @Test
    fun `存在しないファイルは拡張機能ではない`() {
        assertFalse(isWebExtensionArchive(File(temporaryFolder.root, "missing.zip")))
    }

    private fun createZip(fileName: String, entryNames: List<String>): File {
        val file = temporaryFolder.newFile(fileName)
        ZipOutputStream(file.outputStream()).use { zip ->
            entryNames.forEach { entryName ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write("{}".toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }
}
