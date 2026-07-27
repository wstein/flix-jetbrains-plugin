package org.flixlang.intellij.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.FlexAdapter
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import org.flixlang.intellij.lang.parser.FlixParser
import org.flixlang.intellij.lang.psi.FlixTypes

class FlixParserDefinition : ParserDefinition {
    override fun createLexer(project: Project): Lexer = FlexAdapter(_FlixLexer(null))

    override fun createParser(project: Project): PsiParser = FlixParser()

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getCommentTokens(): TokenSet =
        TokenSet.create(FlixTypes.COMMENT_LINE, FlixTypes.COMMENT_BLOCK, FlixTypes.COMMENT_DOC)

    override fun getStringLiteralElements(): TokenSet =
        TokenSet.create(FlixTypes.LITERAL_STRING, FlixTypes.LITERAL_CHAR)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = FlixFile(viewProvider)

    override fun createElement(node: ASTNode): PsiElement = FlixTypes.Factory.createElement(node)

    companion object {
        val FILE = IFileElementType(FlixLanguage)
    }
}
