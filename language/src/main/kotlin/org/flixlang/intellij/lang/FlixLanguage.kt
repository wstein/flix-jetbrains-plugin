package org.flixlang.intellij.lang

import com.intellij.lang.Language

object FlixLanguage : Language("Flix") {
    private fun readResolve(): Any = FlixLanguage

    override fun getDisplayName(): String = "Flix"

    override fun isCaseSensitive(): Boolean = true
}
