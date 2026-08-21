package de.wstein.flixplugin;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.features.semanticTokens.DefaultSemanticTokensColorsProvider;
import org.flixlang.intellij.lang.highlighting.FlixSyntaxHighlighter;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * That an effect name is coloured, and that nothing else is taken away from LSP4IJ.
 *
 * <p>The gap is measured here rather than asserted from the docs: LSP4IJ's own provider is asked
 * about {@code effect} and about the standard types beside it, so this fails the day LSP4IJ starts
 * recognising it and the override becomes dead weight.
 */
public class FlixSemanticTokensFeatureTest {

    /** LSP4IJ's default, which is what decides every type this plugin does not claim. */
    private final DefaultSemanticTokensColorsProvider lsp4ij = new DefaultSemanticTokensColorsProvider();

    /**
     * A file that answers nothing.
     *
     * <p>The parameter is declared {@code @NotNull} and never read: the provider is a switch over
     * the token type. A proxy is therefore the whole fixture, and a real one would only be a
     * platform test around a lookup table.
     */
    private final PsiFile anyFile = (PsiFile) Proxy.newProxyInstance(
            PsiFile.class.getClassLoader(),
            new Class<?>[]{PsiFile.class},
            (proxy, method, args) -> method.getReturnType() == boolean.class ? Boolean.FALSE : null);

    @Test
    public void anEffectIsColouredAsTheDeclarationOfOneIs() {
        // The same key FlixSemanticFallbackAnnotator gives an `eff` declaration, so one entry in
        // the colour scheme governs both layers and the colour does not change when the server
        // answers.
        assertEquals(
                FlixSyntaxHighlighter.Companion.getEFFECT_NAME(),
                FlixSemanticTokensFeature.flixKeyFor("effect"));
    }

    @Test
    public void theTokenTypeIsSpelledAsItGoesOverTheWire() {
        // The trap. The compiler's constant is `Effect` and this plugin's prose calls it that, so a
        // mapping written against the capitalised name compiles, runs, matches nothing and colours
        // nothing -- the same shape as the token-name bug in FlixSyntaxHighlighter.
        assertNull("the wire value is lowercase", FlixSemanticTokensFeature.flixKeyFor("Effect"));
        assertEquals("effect", FlixSemanticTokensFeature.EFFECT);
    }

    @Test
    public void lsp4ijStillPaintsNothingForAnEffect() {
        // Why the override exists. If this ever starts returning a key, LSP4IJ has learned the type
        // and the override should be reconsidered rather than left to shadow it.
        assertNull(
                "LSP4IJ now recognises `effect`; re-check whether this plugin should still override it",
                lsp4ij.getTextAttributesKey("effect", List.of(), anyFile));
    }

    @Test
    public void everyStandardTypeFlixEmitsIsLeftToLsp4ij() {
        // The other half: the override must claim exactly one type. These are the token types
        // SemanticTokensProvider actually emits, measured against a file with one of each
        // declaration -- so a rule that over-claimed would take a decision away from a user or
        // plugin that registered a colours provider of their own.
        for (String standard : List.of(
                "namespace", "type", "class", "enum", "interface", "struct", "typeParameter",
                "parameter", "variable", "property", "enumMember", "function", "method",
                "keyword", "modifier", "comment", "string", "number", "operator")) {
            assertNull(
                    standard + " must be left to LSP4IJ",
                    FlixSemanticTokensFeature.flixKeyFor(standard));
            assertNotNull(
                    "LSP4IJ no longer colours the standard type " + standard,
                    lsp4ij.getTextAttributesKey(standard, List.of(), anyFile));
        }
    }

    @Test
    public void anUnknownTypeIsNotClaimedEither() {
        // A type from neither set is LSP4IJ's to refuse, not this plugin's to guess at.
        assertNull(FlixSemanticTokensFeature.flixKeyFor("decorator"));
        assertNull(FlixSemanticTokensFeature.flixKeyFor("somethingNobodyDefined"));
    }

    @Test
    public void theEffectKeyIsTheOneTheColourSchemeOffers() {
        // A key with no descriptor on the settings page is unreachable, and this one is defined in
        // `language` precisely so both layers and the page can share it.
        TextAttributesKey key = FlixSyntaxHighlighter.Companion.getEFFECT_NAME();
        assertEquals("FLIX_EFFECT_NAME", key.getExternalName());
    }
}
