package org.flixlang.intellij.editor

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import org.flixlang.intellij.lang.FlixLanguage

/**
 * Where the Flix live templates apply.
 *
 * A template without a context type is offered in every file the IDE can open, so `enum` would
 * expand to Flix syntax in a Java or Markdown file. Deciding it on the *language* rather than the
 * file extension means an injected or scratch Flix fragment gets them too.
 *
 * The `contextId` in the descriptor is what the template file's `<option name="…">` refers to; the
 * two are matched by string, so a rename in one place silently disables every template.
 * `FlixLiveTemplatesTest` holds them together.
 */
class FlixTemplateContextType : TemplateContextType("Flix") {

    override fun isInContext(context: TemplateActionContext): Boolean =
        context.file.language.isKindOf(FlixLanguage)
}
