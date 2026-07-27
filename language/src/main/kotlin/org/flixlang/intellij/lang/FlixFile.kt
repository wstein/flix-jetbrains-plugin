package org.flixlang.intellij.lang

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

class FlixFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, FlixLanguage) {
    override fun getFileType(): FileType = FlixFileType.INSTANCE

    override fun toString(): String = "Flix File"
}
