package org.flixlang.intellij.lang.editor

import com.intellij.lang.Commenter

/**
 * `///` doc comments (COMMENT_DOC) are just line comments with an extra leading slash (see
 * _Flix.flex / Lexer.scala's slash-count disambiguation) -- toggling `//` on a doc-comment line
 * still produces a valid (four-slash) line comment, so no separate doc-comment prefix is needed
 * here. Block comments nest in Flix, but `getCommentedBlockCommentPrefix/Suffix` (used to escape
 * an already-present block-comment terminator when wrapping a further block comment around
 * existing commented text) has no natural answer for a nesting-comment language, so this returns
 * null like most Commenter implementations that don't support that edge case.
 */
class FlixCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "//"

    override fun getBlockCommentPrefix(): String = "/*"

    override fun getBlockCommentSuffix(): String = "*/"

    override fun getCommentedBlockCommentPrefix(): String? = null

    override fun getCommentedBlockCommentSuffix(): String? = null
}
