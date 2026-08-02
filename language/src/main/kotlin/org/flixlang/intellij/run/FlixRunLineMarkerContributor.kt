package org.flixlang.intellij.run

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import org.flixlang.intellij.lang.psi.FlixDefDecl
import org.flixlang.intellij.lang.psi.FlixIdent

/**
 * Gutter run icon next to `def main(...)`. The platform calls [getInfo] once per leaf PSI element
 * in the file, so this must fire on exactly one leaf per matching `defDecl` -- the single token
 * inside its name's [FlixIdent] node, identified via [namePsiOrNull] -- not on every other leaf
 * (`DEF_KW`, `COLON`, `EQUAL`, ...) that also happens to descend from the same `defDecl` node.
 *
 * `ident` gained its own PSI node when it stopped being `private` in Flix.bnf (see
 * `FlixDefNames.kt`), so the name leaf's *parent* is now a [FlixIdent], not the `defDecl` itself
 * directly -- one level deeper than before.
 */
class FlixRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (!isRunnableEntryPointAnchor(element)) return null
        return withExecutorActions(AllIcons.RunConfigurations.TestState.Run)
    }

    companion object {
        /**
         * Whether [element] is the single leaf that should carry the run icon.
         *
         * Split out from [getInfo] so the anchoring rule -- the part that decides which *line* the
         * icon lands on -- can be tested on parsed PSI alone. Building the [Info] calls
         * [withExecutorActions], which needs a real
         * [com.intellij.openapi.actionSystem.ActionManager]; a parser-level fixture has none, and
         * standing one up would test the platform rather than this decision.
         */
        fun isRunnableEntryPointAnchor(element: PsiElement): Boolean {
            val ident = element.parent as? FlixIdent ?: return false
            val defDecl = ident.parent as? FlixDefDecl ?: return false
            return defDecl.nameOrNull() == "main" && ident === defDecl.namePsiOrNull()
        }
    }
}
