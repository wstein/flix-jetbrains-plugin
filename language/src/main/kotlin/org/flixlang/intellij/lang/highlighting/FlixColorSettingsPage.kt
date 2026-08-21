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
 * Because without it the plugin's colour keys are unreachable. A key renders through its fallback,
 * or through the default [FlixControlFlowAnnotator] applies, without anything being stored in the
 * scheme — which is what makes adding one a visual no-op, and equally what makes it something no
 * user can ever change. This page is what turns "we defined a key" into "you can decide what it
 * looks like".
 *
 * The corresponding limitation is worth stating: the preview renders from what the *scheme* holds,
 * so a default the annotator computes at paint time -- the bold on a guard keyword, the band behind
 * a condition -- does not appear here until someone stores a value. The tags below still show
 * *which* ranges each key governs, which is the part a reader needs in order to choose.
 *
 * ## Why the demo text is what it is
 *
 * The demo puts all four grammatical roles of `if` on one screen — a conditional expression, a
 * `match` guard, a comprehension guard and a Datalog constraint — beside the `case` that declares
 * an `enum` member and is not control flow at all. That set is not decoration: `IF_KW` really does
 * appear in four separate productions (`Flix.bnf:790`, `:825`, `:845`, `:1024`), and the Datalog
 * one is not a conditional.
 *
 * Three of those roles exist only in the PSI, and this preview is lexed rather than parsed, so they
 * are marked up with the tags in [getAdditionalHighlightingTagToDescriptorMap]. Without that the
 * preview would render every `if` identically — the exact confusion the page is here to undo.
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

    /**
     * The keys [FlixControlFlowAnnotator] assigns, which the preview's own lexer cannot produce.
     *
     * The page renders the demo with the syntax highlighter alone -- no parser, so no annotator --
     * and these three roles exist only in the PSI. Without the tags the preview would show all four
     * `if`s identically, which is the opposite of what the page is for.
     */
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> =
        mapOf(
            "guard" to FlixSyntaxHighlighter.GUARD_KEYWORD,
            "datalogGuard" to FlixSyntaxHighlighter.DATALOG_GUARD_KEYWORD,
            "condition" to FlixSyntaxHighlighter.CONDITION,
            "fn" to FlixSyntaxHighlighter.FUNCTION_NAME,
            "param" to FlixSyntaxHighlighter.PARAMETER,
            "tparam" to FlixSyntaxHighlighter.TYPE_PARAMETER,
            "local" to FlixSyntaxHighlighter.LOCAL_VARIABLE,
            "type" to FlixSyntaxHighlighter.TYPE_NAME,
            "case" to FlixSyntaxHighlighter.ENUM_CASE,
            "field" to FlixSyntaxHighlighter.FIELD_NAME,
            "trait" to FlixSyntaxHighlighter.TRAIT_NAME,
            "eff" to FlixSyntaxHighlighter.EFFECT_NAME,
        )

    private companion object {

        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keyword", FlixSyntaxHighlighter.KEYWORD),
            AttributesDescriptor("Control flow keyword", FlixSyntaxHighlighter.CONTROL_FLOW_KEYWORD),
            AttributesDescriptor("Control flow//Guard keyword", FlixSyntaxHighlighter.GUARD_KEYWORD),
            AttributesDescriptor(
                "Control flow//Datalog constraint keyword",
                FlixSyntaxHighlighter.DATALOG_GUARD_KEYWORD,
            ),
            AttributesDescriptor("Control flow//Condition", FlixSyntaxHighlighter.CONDITION),
            AttributesDescriptor("String", FlixSyntaxHighlighter.STRING),
            AttributesDescriptor("Number", FlixSyntaxHighlighter.NUMBER),
            AttributesDescriptor("Comment", FlixSyntaxHighlighter.COMMENT),
            AttributesDescriptor("Doc comment", FlixSyntaxHighlighter.DOC_COMMENT),
            AttributesDescriptor("Annotation", FlixSyntaxHighlighter.ANNOTATION),
            AttributesDescriptor("Bad character", FlixSyntaxHighlighter.BAD_CHARACTER),
            // The roles the parser knows and the lexer cannot: assigned by
            // FlixSemanticFallbackAnnotator, and repainted by the language server's semantic tokens
            // once it answers. Grouped so a reader retuning "names" finds them together.
            AttributesDescriptor("Names//Function", FlixSyntaxHighlighter.FUNCTION_NAME),
            AttributesDescriptor("Names//Parameter", FlixSyntaxHighlighter.PARAMETER),
            AttributesDescriptor("Names//Type parameter", FlixSyntaxHighlighter.TYPE_PARAMETER),
            AttributesDescriptor("Names//Local variable", FlixSyntaxHighlighter.LOCAL_VARIABLE),
            AttributesDescriptor("Names//Type", FlixSyntaxHighlighter.TYPE_NAME),
            AttributesDescriptor("Names//Enum case", FlixSyntaxHighlighter.ENUM_CASE),
            AttributesDescriptor("Names//Struct field", FlixSyntaxHighlighter.FIELD_NAME),
            AttributesDescriptor("Names//Trait", FlixSyntaxHighlighter.TRAIT_NAME),
            AttributesDescriptor("Names//Effect", FlixSyntaxHighlighter.EFFECT_NAME),
        )

        val DEMO_TEXT = """
            ///
            /// Every role `if` plays, and every name the parser can put a colour on.
            ///
            mod Trains {

                type alias <type>Station</type> = String

                eff <eff>Delay</eff> {
                    def <fn>report</fn>(<param>minutes</param>: Int32): Unit
                }

                trait <trait>Describable</trait>[<tparam>a</tparam>] {
                    pub def <fn>describe</fn>(<param>x</param>: <tparam>a</tparam>): String
                }

                struct <type>Journey</type>[<tparam>r</tparam>] {
                    mut <field>legs</field>: Int32,
                    <field>label</field>: String
                }

                enum <type>Leg</type> {
                    // `case` here declares an enum member. It is not a match arm, and it is not
                    // control flow -- which is why the lexer leaves it a plain keyword.
                    case <case>Direct</case>(<type>Station</type>, <type>Station</type>)
                    case <case>Change</case>(<type>Station</type>, <type>Station</type>, Int32)
                }

                @Test
                def <fn>label</fn>(<param>t</param>: Int32): String =
                    // A conditional expression. The parentheses are required by the grammar,
                    // not chosen by the author.
                    if <condition>(t <= 9)</condition> "0${'$'}{t}" else "${'$'}{t}"

                def <fn>describe</fn>(<param>leg</param>: <type>Leg</type>): String =
                    match leg {
                        // A match guard. Same `if`, no parentheses this time.
                        case Change(_, _, <local>wait</local>) <guard>if</guard> <condition>wait > 30</condition> => "a long change"
                        case Change(_, _, _)                 => "a change"
                        case Direct(<local>from</local>, <local>to</local>)                => "${'$'}{from} to ${'$'}{to}"
                    }

                def <fn>reachable</fn>(): #{ Edge(<type>Station</type>, <type>Station</type>), Path(<type>Station</type>, <type>Station</type>) } = #{
                    // A Datalog constraint. Spelled exactly like a conditional and not one:
                    // it filters the rule's solutions rather than choosing a branch.
                    Path(x, z) :- Path(x, y), Edge(y, z), <datalogGuard>if</datalogGuard> <condition>(x != z)</condition>.
                }

                def <fn>summarise</fn>(<param>legs</param>: List[<type>Leg</type>]): Unit \ IO =
                    let <local>total</local> = List.length(legs);
                    foreach (<local>leg</local> <- legs)
                        // A comprehension guard -- the fourth `if`, and the fourth meaning.
                        <guard>if</guard> <condition>describe(leg) != ""</condition>
                            println("${'$'}{total}: ${'$'}{describe(leg)}")

                def <fn>safely</fn>(): Unit \ IO =
                    try println("hello") catch {
                        case <local>e</local>: ##java.lang.Exception => println(e.getMessage())
                    }
            }
        """.trimIndent()
    }
}
