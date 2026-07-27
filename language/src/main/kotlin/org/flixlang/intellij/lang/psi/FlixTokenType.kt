package org.flixlang.intellij.lang.psi

import com.intellij.psi.tree.IElementType
import org.flixlang.intellij.lang.FlixLanguage

class FlixTokenType(debugName: String) : IElementType(debugName, FlixLanguage) {
    override fun toString(): String = "FlixTokenType.${super.toString()}"
}
