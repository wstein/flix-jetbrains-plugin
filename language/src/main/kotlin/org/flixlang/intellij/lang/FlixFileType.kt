package org.flixlang.intellij.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import org.flixlang.intellij.icons.FlixIcons
import javax.swing.Icon

class FlixFileType private constructor() : LanguageFileType(FlixLanguage) {
    override fun getName(): String = "Flix File"

    override fun getDescription(): String = "Flix language file"

    override fun getDefaultExtension(): String = "flix"

    override fun getIcon(): Icon = FlixIcons.FILE

    companion object {
        @JvmField
        val INSTANCE = FlixFileType()
    }
}
