package org.flixlang.intellij.run

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import org.flixlang.intellij.lang.psi.FlixDefDecl

/**
 * Gutter run icon next to `def main(...)`. The platform calls [getInfo] once per leaf PSI element
 * in the file, so this must fire on exactly one leaf per matching `defDecl` -- its name leaf,
 * identified via [namePsiOrNull] -- not on every other leaf (`DEF_KW`, `COLON`, `EQUAL`, ...) that
 * also happens to be a direct child of the same `defDecl` node.
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
            val defDecl = element.parent as? FlixDefDecl ?: return false
            if (defDecl.nameOrNull() != "main") return false
            return element === defDecl.namePsiOrNull()
        }
    }
}
