package org.flixlang.intellij.lang

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import org.flixlang.intellij.lang.psi.FlixStatement

/**
 * Synthesizes Flix PSI fragments by parsing a throwaway dummy file and pulling the relevant node
 * back out -- the standard technique for custom-language plugins to build new PSI without hand-
 * assembling AST nodes (see the SDK's "Modifying the PSI" guide). Shared by every refactoring that
 * needs to insert new syntax (Introduce Variable, Inline Variable, Extract Method).
 */
object FlixElementFactory {
    /**
     * Parses [statementText] (a fragment like `let x = 1;\nfoo(x)`) as the body of a throwaway
     * `def`, and returns the resulting [FlixStatement] node -- ready to swap in for an existing
     * statement via `PsiElement.replace(...)`.
     */
    fun createStatementFromText(project: Project, statementText: String): FlixStatement {
        // defDecl's body is `EQUAL statement` directly (Flix.bnf) -- no `{ }` wrapper needed or
        // wanted here: braces would instead require matching blockOrRecordExpr, a different,
        // more constrained production.
        // No leading underscore in the dummy def's name: Flix's lexer tokenizes a leading `_` as
        // the wildcard-pattern token rather than folding it into an identifier, which breaks
        // parsing of the surrounding def signature.
        val dummyFile = PsiFileFactory.getInstance(project).createFileFromText(
            "flixRefactoringDummy.flix",
            FlixLanguage,
            "def flixRefactoringDummy(): Unit =\n$statementText",
        )
        return PsiTreeUtil.findChildOfType(dummyFile, FlixStatement::class.java)
            ?: error("Failed to parse synthesized Flix statement:\n$statementText")
    }
}
