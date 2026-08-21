package de.wstein.flixplugin.debugger

import com.intellij.psi.PsiFile
import com.intellij.testFramework.ParsingTestCase
import org.flixlang.intellij.lang.FlixParserDefinition

/**
 * Which Flix definition a line belongs to -- the rule that separates "the next line of this
 * function" from "the next line of something it called", and so the rule that makes Step Over
 * different from Step Into.
 *
 * Exercised against really parsed Flix rather than stubs, because the interesting cases are all
 * about tree shape: indented body lines, blank lines between declarations, and lines that are
 * inside no `def` at all. A stub would encode whatever shape the implementation already assumes.
 */
class FlixDefinitionScopeTest : ParsingTestCase("", "flix", FlixParserDefinition()) {

    override fun getTestDataPath(): String = ""

    override fun skipSpaces(): Boolean = false

    private fun parse(code: String): PsiFile = createPsiFile("Main", code).also { ensureParsed(it) }

    private fun scopeAt(code: String, line: Int): String? =
        FlixDefinitionScope.keyAt(parse(code), line)

    private val twoDefs = """
        def helper(): Int32 =
            let x = 1;
            x

        def main(): Unit \ IO =
            println(helper());
            println("done")
    """.trimIndent()

    fun testLinesOfOneDefinitionShareAScope() {
        // Every line of `main`, including its signature, must answer the same key -- otherwise a
        // step over one statement would think it had left the function and run on.
        val signature = scopeAt(twoDefs, 4)
        assertNotNull("the def signature line must resolve to a scope", signature)
        assertEquals(signature, scopeAt(twoDefs, 5))
        assertEquals(signature, scopeAt(twoDefs, 6))
    }

    fun testDifferentDefinitionsHaveDifferentScopes() {
        // The property the whole feature rests on: stepping over `println(helper())` must be able
        // to tell that a line inside `helper` is somewhere else.
        val inHelper = scopeAt(twoDefs, 1)
        val inMain = scopeAt(twoDefs, 5)
        assertNotNull(inHelper)
        assertNotNull(inMain)
        assertFalse("helper and main must not share a scope", inHelper == inMain)
    }

    fun testIndentedBodyLinesResolve() {
        // The case that would silently disable the feature. Body lines start with whitespace, and
        // if those resolved to null the scope would be unknown for exactly the lines a step lands
        // on -- Step Over would quietly behave as Step Into with no error anywhere.
        assertNotNull("an indented body line must resolve", scopeAt(twoDefs, 2))
    }

    fun testALineOutsideAnyDefinitionHasNoScope() {
        // A blank line between declarations belongs to no def. Returning some neighbouring def's
        // key would confine a step to a function the user is not in.
        assertNull(scopeAt(twoDefs, 3))
    }

    fun testTwoDefinitionsSharingANameStillDiffer() {
        // The identity is the declaration's offset, not its name. Were it the name, these two would
        // merge into one scope and a step over the call would stop inside the callee.
        //
        // `compute` rather than `run`: `run` is a Flix keyword (`run expr with handler`), so
        // `def run` does not parse -- confirmed correct, the upstream corpus contains no `def run`.
        val code = """
            mod A {
                def compute(): Int32 = 1
            }

            mod B {
                def compute(): Int32 = 2
            }
        """.trimIndent()
        val inA = scopeAt(code, 1)
        val inB = scopeAt(code, 5)
        assertNotNull(inA)
        assertNotNull(inB)
        assertFalse("same-named defs in different mods must not share a scope", inA == inB)
    }

    fun testOutOfRangeLinesAreRejectedRatherThanThrowing() {
        // A location can report a line the file no longer has, after an edit during a session.
        assertNull(scopeAt(twoDefs, -1))
        assertNull(scopeAt(twoDefs, 9_999))
    }
}
