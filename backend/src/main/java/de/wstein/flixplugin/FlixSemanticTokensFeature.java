package de.wstein.flixplugin;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.client.features.LSPSemanticTokensFeature;
import org.flixlang.intellij.lang.highlighting.FlixSyntaxHighlighter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Colours the semantic token types the standard does not have a name for.
 *
 * <h2>The gap</h2>
 *
 * LSP 3.16 fixes a set of token types and lets a server add its own. Flix adds exactly one,
 * {@code effect}, because an effect is not a class, an interface or a type parameter and calling it
 * one of those would be a lie a reader can see.
 *
 * LSP4IJ's {@code DefaultSemanticTokensColorsProvider} switches over the standard set and returns
 * {@code null} for everything else, and a {@code null} key means the span is not painted at all. So
 * an effect name — which appears in nearly every Flix signature, after the {@code \} of a type — was
 * the one thing the server took the trouble to identify and no layer coloured. It read as ordinary
 * text.
 *
 * <h2>Why this key</h2>
 *
 * {@link FlixSyntaxHighlighter#EFFECT_NAME}, the same key
 * {@code FlixSemanticFallbackAnnotator} gives an {@code eff} declaration. One entry in
 * Settings → Editor → Color Scheme → Flix then governs both layers, and the colour does not change
 * when the server answers — which is the rule the rest of that fallback is built on.
 *
 * <h2>What is delegated, and to what</h2>
 *
 * Everything else goes to {@code super}, which is not the same as calling the default provider.
 * {@link LSPSemanticTokensFeature#getTextAttributesKey} routes through the <em>server
 * definition's</em> colours provider, so a user or another plugin that registers a
 * {@code semanticTokensColorsProvider} still decides the standard types. Calling the default
 * directly would quietly take that decision away.
 *
 * <h2>The trap in the name</h2>
 *
 * The wire value is lowercase {@code "effect"}. The compiler's enum constant is {@code Effect} and
 * this plugin's prose calls it that, so a mapping written against {@code "Effect"} compiles, runs,
 * matches nothing and colours nothing — the same shape of failure as the token-name bug in
 * {@code FlixSyntaxHighlighter}. Pinned by test.
 */
final class FlixSemanticTokensFeature extends LSPSemanticTokensFeature {

    /**
     * The token type Flix adds to the standard set.
     *
     * <p>Lowercase because that is what goes over the wire — see the class docs.
     */
    static final String EFFECT = "effect";

    @Override
    public @Nullable TextAttributesKey getTextAttributesKey(
            @NotNull String tokenType,
            @NotNull List<String> tokenModifiers,
            @NotNull PsiFile file) {
        TextAttributesKey own = flixKeyFor(tokenType);
        return own != null ? own : super.getTextAttributesKey(tokenType, tokenModifiers, file);
    }

    /**
     * The key for a token type Flix defines itself, or {@code null} for one the standard covers.
     *
     * <p>Separated from the override so the decision can be tested without a server: everything
     * around it needs a started language server, a {@code PsiFile} and a server definition, none of
     * which this rule consults.
     */
    static @Nullable TextAttributesKey flixKeyFor(@NotNull String tokenType) {
        return EFFECT.equals(tokenType) ? FlixSyntaxHighlighter.Companion.getEFFECT_NAME() : null;
    }
}
