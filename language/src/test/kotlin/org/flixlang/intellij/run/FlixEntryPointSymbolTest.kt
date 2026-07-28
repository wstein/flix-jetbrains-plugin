package org.flixlang.intellij.run

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import org.flixlang.intellij.lang.FlixParserDefinition
import org.flixlang.intellij.lang.psi.FlixDefDecl

/**
 * The symbol handed to the compiler's `--entrypoint`.
 *
 * Worth pinning because the compiler's own parsing of it is asymmetric: `Symbol.mkDefnSym` splits at
 * the **last** dot, so a wrong namespace does not fail loudly — it resolves to a different symbol,
 * or to nothing, and the run configuration silently executes something other than what the user
 * clicked.
 */
class FlixEntryPointSymbolTest : ParsingTestCase("", "flix", FlixParserDefinition()) {

    override fun getTestDataPath(): String = ""

    override fun skipSpaces(): Boolean = false

    private fun symbols(code: String): List<String?> {
        val file = createPsiFile("Main", code)
        ensureParsed(file)
        return PsiTreeUtil.findChildrenOfType(file, FlixDefDecl::class.java)
            .map { it.entryPointSymbolOrNull() }
    }

    fun testATopLevelDefIsJustItsName() {
        assertEquals(listOf("main"), symbols("def main(): Unit = ()"))
    }

    fun testADefInAModuleIsQualified() {
        assertEquals(listOf("A.compute"), symbols("mod A {\n    def compute(): Int32 = 1\n}"))
    }

    fun testNestedModulesNestOutwards() {
        // The ordering trap: the enclosing chain is walked inwards-out, so without reversing it this
        // produces B.A.demo -- a symbol that parses fine and names nothing.
        assertEquals(
            listOf("A.B.demo"),
            symbols("mod A {\n    mod B {\n        def demo(): Int32 = 1\n    }\n}"),
        )
    }

    fun testADottedModuleNameSurvivesWhole() {
        // `mod A.B { ... }` and `mod A { mod B { ... } }` are two spellings of one namespace and must
        // produce the same symbol, or a configuration created from one fails to match the other.
        assertEquals(listOf("A.B.demo"), symbols("mod A.B {\n    def demo(): Int32 = 1\n}"))
    }

    fun testEachDefInAFileGetsItsOwnSymbol() {
        val code = """
            def helper(): Int32 = 1

            mod A {
                def compute(): Int32 = 2
            }

            def main(): Unit = ()
        """.trimIndent()
        assertEquals(listOf("helper", "A.compute", "main"), symbols(code))
    }
}
