package dev.wstein.flixplugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser

class FlixDiagramToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val browser = JBCefBrowser()
        val defaultHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    body { font-family: system-ui, sans-serif; padding: 20px; color: #a9b7c6; background-color: #2b2b2b; }
                    .card { border: 1px solid #4e5254; border-radius: 8px; padding: 16px; margin-top: 16px; background-color: #3c3f41; }
                    h2 { margin-top: 0; color: #589df6; }
                </style>
            </head>
            <body>
                <h2>Flix Visual Diagrams</h2>
                <p>Select a Flix symbol or click <code>[View Diagram]</code> in hover tooltips to render SVG visual hierarchy and Datalog relation schemas.</p>
                <div class="card">
                    <h3>Features Available:</h3>
                    <ul>
                        <li><b>Trait Hierarchy:</b> Supertraits and sub-instances</li>
                        <li><b>Module Structure:</b> Submodules and declaration scopes</li>
                        <li><b>Datalog EDB/IDB Schemas:</b> Extensional facts vs Intensional derived rules</li>
                        <li><b>Stratification Cycle Diagnostic:</b> Negation cycle visualizer</li>
                    </ul>
                </div>
            </body>
            </html>
        """.trimIndent()
        browser.loadHTML(defaultHtml)

        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(browser.component, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
