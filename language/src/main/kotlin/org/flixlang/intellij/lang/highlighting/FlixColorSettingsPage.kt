package org.flixlang.intellij.lang.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import org.flixlang.intellij.icons.FlixIcons
import javax.swing.Icon

/**
 * Settings > Editor > Color Scheme > Flix.
 *
 * ## Why this exists at all
 *
 * Because without it the plugin's colour keys are unreachable. Every key
 * [FlixSyntaxHighlighter] defines falls back to a platform default, which is what makes adding one
 * a visual no-op — but a key with no page is also a key no user can ever change. This page is what
 * turns "we defined a key" into "you can decide what it looks like", and it is therefore a
 * prerequisite for the control-flow key rather than a nicety alongside it.
 *
 * ## Why the demo text is what it is
 *
 * The preview renders with whatever the scheme currently says, so with neutral defaults it cannot
 * demonstrate a colour nobody has chosen yet. What it *can* demonstrate is the thing the key is
 * for: the demo below puts all four grammatical roles of `if` in one screen — a conditional
 * expression, a `match` guard, a comprehension guard and a Datalog constraint — so that anyone
 * picking a colour can see at once what does and does not get it.
 *
 * That set is not decoration. `IF_KW` really does appear in four separate productions
 * (`Flix.bnf:790`, `:825`, `:845`, `:1024`), and the Datalog one is not a conditional at all.
 */
class FlixColorSettingsPage : ColorSettingsPage {

    override fun getDisplayName(): String = "Flix"

    override fun getIcon(): Icon = FlixIcons.FILE

    override fun getHighlighter(): SyntaxHighlighter = FlixSyntaxHighlighter()

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    /**
     * Lexed by [getHighlighter], not parsed, so this never has to compile.
     *
     * It is still written as real Flix: a preview that does not look like the language teaches the
     * reader nothing about their own files.
     */
    override fun getDemoText(): String = DEMO_TEXT

    /** No annotator-driven keys yet, so the demo needs no `<tag>` markup. */
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    private companion object {

        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keyword", FlixSyntaxHighlighter.KEYWORD),
            AttributesDescriptor("Control flow keyword", FlixSyntaxHighlighter.CONTROL_FLOW_KEYWORD),
            AttributesDescriptor("String", FlixSyntaxHighlighter.STRING),
            AttributesDescriptor("Number", FlixSyntaxHighlighter.NUMBER),
            AttributesDescriptor("Comment", FlixSyntaxHighlighter.COMMENT),
            AttributesDescriptor("Annotation", FlixSyntaxHighlighter.ANNOTATION),
            AttributesDescriptor("Bad character", FlixSyntaxHighlighter.BAD_CHARACTER),
        )

        val DEMO_TEXT = """
            ///
            /// Every role `if` plays, on one screen.
            ///
            mod Trains {

                type alias Station = String

                enum Leg {
                    // `case` here declares an enum member. It is not a match arm, and it is not
                    // control flow -- which is why the lexer leaves it a plain keyword.
                    case Direct(Station, Station)
                    case Change(Station, Station, Int32)
                }

                @Test
                def label(t: Int32): String =
                    // A conditional expression. The parentheses are required by the grammar,
                    // not chosen by the author.
                    if (t <= 9) "0${'$'}{t}" else "${'$'}{t}"

                def describe(leg: Leg): String =
                    match leg {
                        // A match guard. Same `if`, no parentheses this time.
                        case Change(_, _, wait) if wait > 30 => "a long change"
                        case Change(_, _, _)                 => "a change"
                        case Direct(from, to)                => "${'$'}{from} to ${'$'}{to}"
                    }

                def reachable(): #{ Edge(Station, Station), Path(Station, Station) } = #{
                    // A Datalog constraint. Spelled exactly like a conditional and not one:
                    // it filters the rule's solutions rather than choosing a branch.
                    Path(x, z) :- Path(x, y), Edge(y, z), if (x != z).
                }

                def report(legs: List[Leg]): Unit \ IO =
                    foreach (leg <- legs)
                        // A comprehension guard -- the fourth `if`, and the fourth meaning.
                        if (describe(leg) != "")
                            println(describe(leg))

                def safely(): Unit \ IO =
                    try println("hello") catch {
                        case e: ##java.lang.Exception => println(e.getMessage())
                    }
            }
        """.trimIndent()
    }
}
