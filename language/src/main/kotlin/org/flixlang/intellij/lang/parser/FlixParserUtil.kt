package org.flixlang.intellij.lang.parser

import com.intellij.lang.PsiBuilder
import com.intellij.lang.parser.GeneratedParserUtilBase
import com.intellij.psi.tree.IElementType
import org.flixlang.intellij.lang.psi.FlixTypes

/**
 * Grammar-Kit `parserUtilClass`: FlixParser.java resolves both the generated code's own inherited
 * helpers (recursion_guard_, consumeToken, report_error_, ...) and this object's own methods via a
 * single `import static ...FlixParserUtil.*`, since Java's static import follows the class
 * hierarchy -- extending [GeneratedParserUtilBase] here is what keeps every existing generated call
 * working exactly as it did with the implicit default. `@JvmStatic` is required on every member
 * referenced from an `<<external>>` rule in Flix.bnf: Grammar-Kit calls them as plain static Java
 * methods (`FlixParserUtil.methodName(builder_, level_)`), not through Kotlin's `INSTANCE` field.
 *
 * [consumeUntilTraitMemberStart] mirrors Parser2.scala's own hand-written trait-body recovery loop
 * (`while (!nth(0).isFirstInTraitDecl && !eat(TokenKind.CurlyR) && !eof()) { advance() }`): skip
 * tokens one at a time until either a token that can start a new trait member is reached (left
 * unconsumed, for the *next* traitMember attempt to pick up) or CURLY_R is reached (consumed, since
 * that's the trait body's own closing brace). This is the one thing a declarative Grammar-Kit rule
 * can't express: "consume any token, of whichever kind, repeatedly" has no built-in wildcard, and
 * enumerating the ~150 non-trait-member-start tokens by hand would be both unwieldy and silently
 * wrong the moment a new token is added elsewhere in the grammar.
 */
object FlixParserUtil : GeneratedParserUtilBase() {

    private val TRAIT_MEMBER_START: Set<IElementType> = setOf(
        FlixTypes.LAW_KW, FlixTypes.DEF_KW, FlixTypes.TYPE_KW,
        FlixTypes.PUB_KW, FlixTypes.SEALED_KW, FlixTypes.LAWFUL_KW, FlixTypes.MUT_KW,
        FlixTypes.ANNOTATION, FlixTypes.COMMENT_DOC,
    )

    @JvmStatic
    fun consumeUntilTraitMemberStart(builder: PsiBuilder, level: Int): Boolean {
        // Parser2.scala's own recovery loop is only ever entered from the `case at =>` branch of
        // an outer `nth(0) match` that has *already* ruled out `CurlyR` via its own separate
        // `case CurlyR => continue = false` branch -- so by the time that loop's inner
        // `!eat(CurlyR)` check can fire, at least one non-CurlyR token has necessarily been
        // skipped already. This flat external rule collapses both checks into one function, so it
        // needs the same guard explicitly: if CURLY_R is the very *first* token seen, with nothing
        // skipped yet, that's the trait body's own clean, legitimate close (e.g. right after a
        // well-formed last member) -- return false and leave it untouched for traitBody's own
        // trailing CURLY_R to consume normally. Only once something else has already been skipped
        // does reaching CURLY_R mean "this is where the garbage run ends", at which point it's
        // consumed as part of that run, matching Parser2.scala exactly.
        if (builder.tokenType == FlixTypes.CURLY_R) return false
        var consumedAny = false
        while (true) {
            val tokenType = builder.tokenType ?: break
            if (tokenType in TRAIT_MEMBER_START) break
            if (tokenType == FlixTypes.CURLY_R) {
                builder.advanceLexer()
                consumedAny = true
                break
            }
            builder.advanceLexer()
            consumedAny = true
        }
        return consumedAny
    }
}
