package de.wstein.flixplugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingConstants

/**
 * Where a Flix diagram is shown.
 *
 * The diagram arrives from the language server as a complete SVG document, so this only has to host
 * it. Held as a project service rather than built by the factory so that `FlixShowDiagramAction`
 * can push a diagram in without first locating the tool window's content, and so that asking for a
 * diagram before the tool window has ever been opened still works.
 *
 * It lives in `backend` rather than `frontend` because it is driven by LSP4IJ, which is a
 * monolithic plugin -- it declares no content modules, so in split mode it and everything calling
 * it run host-side. A tool window registered here is proxied to the client exactly as LSP4IJ's own
 * UI is.
 */
@Service(Service.Level.PROJECT)
class FlixDiagramView(private val project: Project) : Disposable {

    /**
     * `null` when the IDE cannot run an embedded browser.
     *
     * JCEF is absent from some runtimes -- a non-JetBrains JDK most commonly -- and constructing a
     * [JBCefBrowser] there throws. Checking first turns "the tool window is broken" into a message
     * naming what is missing.
     */
    private val browser: JBCefBrowser? =
        if (JBCefApp.isSupported()) JBCefBrowser().also { Disposer.register(this, it) } else null

    val component: JComponent =
        browser?.component ?: JLabel(
            "<html><center>Diagrams need the IDE's embedded browser (JCEF),<br>" +
                "which this runtime does not provide.</center></html>",
            SwingConstants.CENTER,
        )

    init {
        showHtml(placeholder())
    }

    /** Shows [svg] exactly as the server produced it. */
    fun showDiagram(svg: String) = showHtml(page(svg))

    /** Shows a message in place of a diagram -- no diagram for this item, or a failed request. */
    fun showMessage(message: String) = showHtml(page("<p class=\"message\">${escape(message)}</p>"))

    /** Brings the tool window forward, so a diagram never appears somewhere unattended. */
    fun activate() {
        ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId)?.activate(null, true)
    }

    private fun showHtml(html: String) {
        browser?.loadHTML(html)
    }

    override fun dispose() = Unit

    private fun page(body: String): String =
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <style>
                body { font-family: system-ui, sans-serif; margin: 0; padding: 16px;
                       color: #a9b7c6; background-color: #2b2b2b; }
                svg { max-width: 100%; height: auto; }
                .message { color: #cc7832; }
                h2 { margin-top: 0; color: #589df6; }
                code { background-color: #3c3f41; padding: 1px 4px; border-radius: 3px; }
            </style>
        </head>
        <body>$body</body>
        </html>
        """.trimIndent()

    private fun placeholder(): String = page(
        """
        <h2>Flix Diagrams</h2>
        <p>Put the caret on a trait or module and invoke <code>Show Flix Diagram</code>
           to render its structure here.</p>
        <p>Traits show their supertraits; modules show their submodules. An item with neither has
           nothing to draw.</p>
        """.trimIndent(),
    )

    /** Escapes text destined for the page, so a symbol name cannot inject markup. */
    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        const val ToolWindowId: String = "Flix Diagrams"

        fun getInstance(project: Project): FlixDiagramView = project.service()
    }
}

/** Registered under `com.intellij.toolWindow`; the content is owned by [FlixDiagramView]. */
class FlixDiagramToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance()
            .createContent(FlixDiagramView.getInstance(project).component, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
