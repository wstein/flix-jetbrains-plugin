package de.wstein.flixplugin.debugger

import com.intellij.ide.highlighter.ArchiveFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FlixArchiveSourceFilesTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        ApplicationManager.getApplication().runWriteAction {
            FileTypeManager.getInstance().associateExtension(ArchiveFileType.INSTANCE, "fpkg")
        }
    }

    fun testDiscoversAndOpensAnExactPackageEntryWithoutAPreexistingVfsFile() {
        val archive = Files.createTempFile("flix package with spaces ", ".fpkg")
        ZipOutputStream(Files.newOutputStream(archive)).use { output ->
            output.putNextEntry(ZipEntry("src/example/Main.flix"))
            output.write("pub def answer(): Int32 = 42\n".toByteArray(StandardCharsets.UTF_8))
            output.closeEntry()
        }

        val source = FlixSourceFiles.inArchive(archive, "src/example/Main.flix")

        assertNotNull(source)
        assertEquals("pub def answer(): Int32 = 42\n", String(source!!.contentsToByteArray()))
    }
}
