package org.flixlang.intellij.lang

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase

class FlixParsingTest : ParsingTestCase("", "flix", FlixParserDefinition()) {
    override fun getTestDataPath(): String = ""

    override fun skipSpaces(): Boolean = true

    private fun parseAndCheck(code: String) {
        val psiFile = createPsiFile("test", code)
        ensureParsed(psiFile)
        println(toParseTreeText(psiFile, true, false))
        val error = PsiTreeUtil.findChildOfType(psiFile, PsiErrorElement::class.java)
        assertNull("Unexpected parse error: ${error?.errorDescription}", error)
    }

    fun testSimpleDef() {
        parseAndCheck("pub def foo(): Int32 = 123")
    }

    fun testRecordAndComment() {
        parseAndCheck(
            """
            pub def foo(): { x = Int32 } = {
                // This is a comment
                x = 1000
            }
            """.trimIndent()
        )
    }

    fun testExtensibleType() {
        parseAndCheck("pub def foo(): #| A(Int32) |# = ???")
    }

    fun testEnumAndMatch() {
        parseAndCheck(
            """
            enum Shape {
                case Circle(Int32),
                case Square(Int32)
            }

            def area(s: Shape): Int32 = match s {
                case Shape.Circle(r) => r * r
                case Shape.Square(w) => w * w
            }
            """.trimIndent()
        )
    }

    fun testStructAndTrait() {
        parseAndCheck(
            """
            struct Point[r] {
                mut x: Int32,
                mut y: Int32
            }

            trait Show[a] {
                pub def show(x: a): String
            }
            """.trimIndent()
        )
    }

    fun testLambdaAndPipeline() {
        parseAndCheck(
            """
            def main(): Unit \ IO =
                let xs = List#{1, 2, 3};
                let ys = List.map(x -> x + 1, xs);
                println(ys)
            """.trimIndent()
        )
    }

    fun testStringInterpolation() {
        parseAndCheck("""def greet(name: String): String = "Hello, ${'$'}{name}!"""")
    }

    fun testTryCatch() {
        parseAndCheck(
            """
            def safeDiv(x: Int32, y: Int32): Int32 =
                try {
                    x / y
                } catch {
                    case ex: ArithmeticException => 0
                    case ex: RuntimeException => -1
                }
            """.trimIndent()
        )
    }

    fun testEffectHandler() {
        parseAndCheck(
            """
            eff Print {
                pub def print(s: String): Unit
            }

            def demo(): Unit = run {
                Print.print("hello")
            } with handler Print {
                def print(s, k) = k()
            }
            """.trimIndent()
        )
    }
}
