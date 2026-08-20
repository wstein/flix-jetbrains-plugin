package dev.wstein.flixplugin;

import com.intellij.openapi.vfs.VirtualFile;
import com.redhat.devtools.lsp4ij.client.features.FileUriSupport;
import com.redhat.devtools.lsp4ij.features.workspaceFolder.ProjectWorkspaceFolderStrategy;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Names each workspace folder by its absolute path instead of its directory name.
 *
 * <p>The name of a {@code WorkspaceFolder} is, per the LSP specification, "the name of the
 * workspace folder. Used to refer to this workspace folder in the user interface" -- a label. The
 * Flix server reads it as a <em>path</em>:
 *
 * <pre>
 * // LspServer.scala, loadFlixProject
 * root &lt;- roots
 * path = Paths.get(root.getName)
 * if Files.exists(path) &amp;&amp; Files.isDirectory(path)
 * </pre>
 *
 * <p>and that guard is what loads {@code *.flix}, {@code src/**}, {@code test/**}, {@code lib/*.jar}
 * and {@code lib/*.fpkg} into the compiler. LSP4IJ sets the name to {@code file.getName()} -- the
 * directory's base name -- so {@code Paths.get("flix-proc-invaders")} resolves against the server
 * process's working directory, which <em>is</em> the project root, giving
 * {@code <project>/flix-proc-invaders}. That does not exist, the guard fails silently, and no
 * project source is loaded at all.
 *
 * <p>The server still answers, because {@code textDocument/didOpen} adds the one buffer the editor
 * shows. So the failure does not look like a dead server: highlighting, folding and completion all
 * work, while every name defined in another file reports "Undefined type" / "Undefined name". That
 * is the whole symptom.
 *
 * <p>Sending the path as the name costs a correct server nothing -- it is a display label, and the
 * uri is unchanged and still absolute -- and is the only lever the client has here.
 */
final class FlixWorkspaceFolderStrategy extends ProjectWorkspaceFolderStrategy {

    @Override
    protected @Nullable WorkspaceFolder createWorkspaceFolder(@NotNull VirtualFile file,
                                                              @NotNull FileUriSupport fileUriSupport) {
        WorkspaceFolder folder = super.createWorkspaceFolder(file, fileUriSupport);
        if (folder != null) {
            folder.setName(file.getPath());
        }
        return folder;
    }
}
