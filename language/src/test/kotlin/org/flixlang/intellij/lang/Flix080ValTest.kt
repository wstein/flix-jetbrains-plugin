package org.flixlang.intellij.lang

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase

class Flix080ValTest : ParsingTestCase("", "flix", FlixParserDefinition()) {
    override fun getTestDataPath(): String = ""
    override fun skipSpaces(): Boolean = true

    private fun errorIn(code: String): PsiErrorElement? {
        val file = createPsiFile("test", code)
        ensureParsed(file)
        return PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
    }

    fun testModuleValueAndLocalLet() {
        assertNull(errorIn("mod M { pub val field: Int32 = 42 }"))
        assertNull(errorIn("def f(): Int32 = let value = 42; value"))
    }

    fun testValCannotBeAnUnescapedParameter() {
        assertNotNull(errorIn("def f(val: Int32): Int32 = val"))
    }

    fun testValPrefixRemainsAnIdentifier() {
        assertNull(errorIn("def f(valve: Int32): Int32 = valve"))
    }

    fun testValueRequiresInitializer() {
        assertNotNull(errorIn("val field: Int32"))
    }
}
