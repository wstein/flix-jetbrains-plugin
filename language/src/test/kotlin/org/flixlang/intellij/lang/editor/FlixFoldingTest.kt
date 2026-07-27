package org.flixlang.intellij.lang.editor

import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.testFramework.ParsingTestCase
import org.flixlang.intellij.lang.FlixParserDefinition

/**
 * Exercises [FlixFoldingBuilder] against real parsed Flix PSI.
 *
 * The builder is invoked directly rather than through `LanguageFolding.INSTANCE.forLanguage`,
 * because an extension lookup needs the assembled plugin's descriptor and a content-module
 * descriptor is inert outside it -- see FlixPluginDescriptorTest, which asserts the registration
 * itself. Splitting it this way keeps each test honest: this one fails if folding is wrong, that
 * one fails if folding is unregistered, and neither can pass by accident of the other.
 */
class FlixFoldingTest : ParsingTestCase("", "flix", FlixParserDefinition()) {

    override fun getTestDataPath(): String = ""

    override fun skipSpaces(): Boolean = false

    private fun foldingDescriptors(code: String): List<FoldingDescriptor> {
        val file = createPsiFile("Test", code)
        ensureParsed(file)
        return FlixFoldingBuilder()
            .buildFoldRegions(file.node, DocumentImpl(code))
            .toList()
    }

    fun testEnumBodyFolds() {
        val descriptors = foldingDescriptors(
            """
            enum Shape {
                case Circle(Int32),
                case Square(Int32)
            }
            """.trimIndent()
        )
        assertEquals(1, descriptors.size)
        assertEquals("{...}", descriptors.single().placeholderText)
    }

    fun testTraitBodyFolds() {
        val descriptors = foldingDescriptors(
            """
            trait Show[a] {
                pub def show(x: a): String
            }
            """.trimIndent()
        )
        assertEquals(1, descriptors.size)
        assertEquals("{...}", descriptors.single().placeholderText)
    }

    fun testStructBodyFolds() {
        val descriptors = foldingDescriptors(
            """
            struct Point[r] {
                mut x: Int32,
                mut y: Int32
            }
            """.trimIndent()
        )
        assertEquals(1, descriptors.size)
        assertEquals("{...}", descriptors.single().placeholderText)
    }

    fun testDefBlockBodyFolds() {
        val descriptors = foldingDescriptors(
            """
            def foo(): Int32 = {
                1 + 2
            }
            """.trimIndent()
        )
        assertEquals(1, descriptors.size)
        assertEquals("{...}", descriptors.single().placeholderText)
    }

    fun testDefExpressionBodyHasNoFold() {
        val descriptors = foldingDescriptors("def foo(): Int32 = 1 + 2")
        assertTrue(descriptors.isEmpty())
    }

    fun testMatchBlockFolds() {
        val descriptors = foldingDescriptors(
            """
            def area(s: Shape): Int32 = match s {
                case Shape.Circle(r) => r * r
                case Shape.Square(w) => w * w
            }
            """.trimIndent()
        )
        assertEquals(1, descriptors.size)
        assertEquals("{...}", descriptors.single().placeholderText)
    }

    fun testSingleLineBodyIsNotFolded() {
        // Multi-line-only: a brace body that fits on one line has nothing useful to collapse.
        val descriptors = foldingDescriptors("enum Shape { case Circle(Int32) }")
        assertTrue(descriptors.isEmpty())
    }

    fun testInstanceAndEffectBodiesAlsoFold() {
        // Same brace-body mechanism as enum/trait/struct; covered once here since it's the same
        // code path, not a distinctly different one.
        val descriptors = foldingDescriptors(
            """
            eff Print {
                pub def print(s: String): Unit
            }

            instance Show[Point] {
                pub def show(x: Point): String = "Point"
            }
            """.trimIndent()
        )
        assertEquals(2, descriptors.size)
        assertTrue(descriptors.all { it.placeholderText == "{...}" })
    }

    fun testConsecutiveLineCommentsFoldTogether() {
        val descriptors = foldingDescriptors(
            """
            // first
            // second
            // third
            def foo(): Int32 = 1
            """.trimIndent()
        )
        val commentFold = descriptors.singleOrNull { it.placeholderText == "//..." }
        assertNotNull("Expected the three consecutive line comments to fold as one region", commentFold)
    }

    fun testBlankLineSeparatedCommentsDoNotMergeIntoOneGroup() {
        val descriptors = foldingDescriptors(
            """
            // first

            // second
            def foo(): Int32 = 1
            """.trimIndent()
        )
        assertTrue(
            "Isolated single-line comments shouldn't produce a merged '//...' group",
            descriptors.none { it.placeholderText == "//..." }
        )
    }

    fun testMultilineBlockCommentFolds() {
        val descriptors = foldingDescriptors(
            """
            /*
             * a block comment
             */
            def foo(): Int32 = 1
            """.trimIndent()
        )
        assertTrue(descriptors.any { it.placeholderText == "/*...*/" })
    }

    fun testSingleLineBlockCommentIsNotFolded() {
        val descriptors = foldingDescriptors("""/* one line */ def foo(): Int32 = 1""")
        assertTrue(descriptors.none { it.placeholderText == "/*...*/" })
    }
}
