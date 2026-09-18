package de.wstein.flixplugin;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/** Paints compiler coverage beside the first source token of each reported Flix line. */
public final class FlixCoverageLineMarkerProvider implements LineMarkerProvider {
    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!isFirstTokenOnLine(element)) return null;
        PsiFile file = element.getContainingFile();
        Document document = PsiDocumentManager.getInstance(element.getProject()).getDocument(file);
        if (document == null) return null;
        int lineNumber = document.getLineNumber(element.getTextOffset()) + 1;
        FlixCoverageService.Line coverage = element.getProject()
                .getService(FlixCoverageService.class)
                .line(file.getVirtualFile(), lineNumber);
        if (coverage == null) return null;

        String tooltip = tooltip(coverage);
        return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                icon(coverage.getState()),
                ignored -> tooltip,
                null,
                GutterIconRenderer.Alignment.LEFT,
                () -> tooltip);
    }

    static boolean isFirstTokenOnLine(@NotNull PsiElement element) {
        if (element.getFirstChild() != null || element instanceof PsiWhiteSpace) return false;
        PsiFile file = element.getContainingFile();
        Document document = PsiDocumentManager.getInstance(element.getProject()).getDocument(file);
        if (document == null) return false;
        int offset = element.getTextOffset();
        int line = document.getLineNumber(offset);
        int start = document.getLineStartOffset(line);
        int end = document.getLineEndOffset(line);
        CharSequence chars = document.getCharsSequence();
        while (start < end && Character.isWhitespace(chars.charAt(start))) start++;
        return offset == start;
    }

    static @NotNull String tooltip(@NotNull FlixCoverageService.Line line) {
        return switch (line.getState()) {
            case COVERED -> "Coverage: covered (" + line.getHitCount() +
                    (line.getHitCount() == 1 ? " hit)" : " hits)");
            case UNCOVERED -> "Coverage: not covered";
            case PARTIAL -> "Coverage: partial run (" + line.getHitCount() +
                    (line.getHitCount() == 1 ? " hit recorded)" : " hits recorded)");
        };
    }

    private static Icon icon(FlixCoverageService.State state) {
        return switch (state) {
            case COVERED -> AllIcons.General.InspectionsOK;
            case UNCOVERED -> AllIcons.General.Error;
            case PARTIAL -> AllIcons.General.Warning;
        };
    }
}
