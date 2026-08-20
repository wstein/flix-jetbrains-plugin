package dev.wstein.flixplugin;

import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PsiTestUtil;
import com.redhat.devtools.lsp4ij.client.features.FileUriSupport;
import org.eclipse.lsp4j.WorkspaceFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The Flix server resolves a workspace folder's <em>name</em> as a filesystem path, and loads the
 * project's sources only if that path is an existing directory (LspServer.loadFlixProject). These
 * assertions are that guard, restated on the client side.
 *
 * <p>HeavyPlatformTestCase, not BasePlatformTestCase, for the reason FlixForkTest gives: the
 * assertion is about a real directory on disk, which an in-memory {@code temp:/} project does not
 * have. The content root is added explicitly because the roots LSP4IJ asks for are the project's
 * <em>base directories</em>, and a bare test project has none.
 */
public class FlixWorkspaceFolderStrategyTest extends HeavyPlatformTestCase {

    public void testTheFolderNameIsAPathTheServerCanResolve() throws IOException {
        List<WorkspaceFolder> folders =
                new FlixWorkspaceFolderStrategy().getWorkspaceFolders(getProject(), FileUriSupport.DEFAULT);

        assertFalse("no workspace folder to send: the server would be told nothing at all", folders.isEmpty());
        for (WorkspaceFolder folder : folders) {
            // Exactly what the server does with the name. A bare directory name resolves against
            // the server process's working directory instead, and silently loads no sources.
            Path asServerReadsIt = Path.of(folder.getName());
            assertTrue("folder name is not an absolute path: " + folder.getName(),
                    asServerReadsIt.isAbsolute());
            assertTrue("folder name is not an existing directory: " + folder.getName(),
                    Files.isDirectory(asServerReadsIt));
        }
    }

    public void testTheServerFactoryInstallsIt() {
        assertInstanceOf(
                new FlixLanguageServerFactory().createClientFeatures().getWorkspaceFolderFeature().getStrategy(),
                FlixWorkspaceFolderStrategy.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Path base = Path.of(getProject().getBasePath());
        Files.createDirectories(base);
        VirtualFile root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base);
        assertNotNull(root);
        PsiTestUtil.addContentRoot(getModule(), root);
    }
}
