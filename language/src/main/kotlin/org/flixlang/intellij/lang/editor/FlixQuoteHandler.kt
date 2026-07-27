package org.flixlang.intellij.lang.editor

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import org.flixlang.intellij.lang.psi.FlixTypes

/** Covers all three of Flix's quote-delimited literal tokens (char, string, regex). */
class FlixQuoteHandler : SimpleTokenSetQuoteHandler(
    FlixTypes.LITERAL_CHAR,
    FlixTypes.LITERAL_STRING,
    FlixTypes.LITERAL_REGEX,
)
