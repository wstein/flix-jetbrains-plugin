package org.flixlang.intellij.lang

import com.intellij.ide.highlighter.ArchiveFileType
import com.intellij.openapi.fileTypes.FileTypeConsumer
import com.intellij.openapi.fileTypes.FileTypeFactory

/** Associates published Flix source packages with IntelliJ's existing archive file type. */
class FlixPackageFileTypeFactory : FileTypeFactory() {

    override fun createFileTypes(consumer: FileTypeConsumer) {
        consumer.consume(ArchiveFileType.INSTANCE, PACKAGE_EXTENSION)
    }

    private companion object {
        const val PACKAGE_EXTENSION = "fpkg"
    }
}
