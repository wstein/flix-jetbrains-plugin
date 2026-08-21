package de.wstein.flixplugin.debugger

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * That every platform class this module uses comes from a module it declares.
 *
 * A plugin dependency does not bring the depended-on plugin's *content modules* with it. Each has a
 * classloader of its own, and a module that has not asked for one cannot see its classes -- so code
 * that compiles, verifies and runs in the sandbox can still die in an installed IDE the first time
 * it touches a class from an undeclared module.
 *
 * It did: `com.intellij.execution.configurations.RemoteConnection` lives in `intellij.java.execution`
 * and pressing Debug produced `NoClassDefFoundError` at the first line of `FlixLaunch` that mentions
 * it. Nothing caught that. The compiler resolves against the whole distribution, and the Plugin
 * Verifier reported the plugin compatible for the same reason: the class *is* there, and neither is
 * modelling which classloader will be asked for it.
 *
 * ## How it decides
 *
 * The IDE ships each content module as `…/modules/<module id>.jar`, so the module that provides a
 * class is the name of the jar it was loaded from. The test classpath is the same distribution the
 * plugin runs against, so asking the JVM where a class came from answers the question exactly --
 * no index to maintain, and no list to keep in step with the descriptor.
 *
 * Classes from anywhere else -- the platform core, an embedded module -- need no declaration and
 * are ignored.
 *
 * ## What it reads
 *
 * The module's `import` lines, which is a deliberate limit worth stating: a class referenced by its
 * fully qualified name inline is invisible here. This is a guard against the mistake that has
 * happened, not a proof about every reference.
 *
 * A second limit, measured: it judges against the platform this build *compiles* against, and which
 * classes live in a content module changes between releases. `JBCefApp` sits in `lib/` in 2026.1 and
 * in the content module `intellij.platform.ui.jcef` in 2026.2; the same is true of the test-runner
 * console classes `language` uses. Neither module is declared by anything in 2026.1, so a dependency
 * on them cannot be added while that release is supported -- the module would be skipped for want of
 * a module that does not exist there. Recorded in `docs/phase-8-verification.md` rather than guessed
 * at, and this check will report them the moment the compile platform moves.
 */
class FlixPlatformDependenciesTest {

    @Test
    fun `every platform module each content module uses is declared`() {
        // Every module, not only this one: the mistake is a property of the descriptor, and three of
        // the four were making it. `backend` reaches `JBCefApp` for the diagram window and `language`
        // reaches the test-runner console, each from a content module neither had asked for.
        val missing = sortedMapOf<String, MutableList<String>>()
        for (module in MODULES) {
            val declared = declaredModules(module)
            for (imported in platformImports(module)) {
                val provider = providingModule(imported) ?: continue
                if (provider !in declared) {
                    missing.getOrPut("$module -> $provider") { mutableListOf() } += imported
                }
            }
        }

        assertTrue(
            "a module descriptor does not declare what it uses: " +
                missing.entries.joinToString("; ") { (edge, classes) ->
                    "$edge (for ${classes.sorted().joinToString(", ")})"
                } +
                ". A plugin dependency does not bring content modules with it; add each as " +
                "`<module name=\"…\"/>` to that descriptor's dependencies.",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the check can see the distribution it is judging`() {
        // Without this the test above passes by finding nothing, which is the failure mode of every
        // check that resolves its own inputs. A class known to live in a content module must be
        // attributed to one.
        assertTrue("no platform imports were found at all", MODULES.any { platformImports(it).isNotEmpty() })
        assertTrue(
            "RemoteConnection should come from a content module, but was attributed to " +
                providingModule("com.intellij.execution.configurations.RemoteConnection"),
            providingModule("com.intellij.execution.configurations.RemoteConnection") == "intellij.java.execution",
        )
    }

    /** The `com.intellij` classes `module`'s sources import. */
    private fun platformImports(module: String): List<String> {
        val roots = listOf("src/main/kotlin", "src/main/java").map { repositoryRoot().resolve(module).resolve(it) }
        return roots.filter { it.exists() }.flatMap { platformImportsIn(it) }.distinct()
    }

    private fun platformImportsIn(root: Path): List<String> =
        Files.walk(root).use { paths ->
            paths.filter { it.extension == "kt" || it.extension == "java" }
                .flatMap { path -> path.readText().lineSequence().asStream() }
                .map { it.trim() }
                .filter { it.startsWith("import com.intellij.") }
                .map { it.removePrefix("import ").substringBefore(" as ") }
                .distinct()
                .toList()
        }

    /**
     * The content module `className` is shipped in, or `null` if it needs no declaration.
     *
     * `null` covers the platform core and modules loaded into the plugin's own classloader, neither
     * of which is a separate dependency, and a class that cannot be loaded at all -- which the test
     * classpath makes unlikely and which is not this test's business to report.
     */
    private fun providingModule(className: String): String? {
        // Asked as a *resource* rather than through the loaded class's code source: the test
        // framework's classloader reports none, and a `jar:file:…!/…` URL says the same thing
        // without needing the class to be initialised.
        val url = javaClass.classLoader.getResource(className.replace('.', '/') + ".class")?.toString()
            ?: return null
        val jar = url.substringBefore("!/").substringAfterLast('/')
        return jar.removeSuffix(".jar").takeIf { url.contains("/modules/") && jar.endsWith(".jar") }
    }

    /** The modules `module`'s descriptor declares as dependencies. */
    private fun declaredModules(module: String): Set<String> {
        val descriptor = repositoryRoot().resolve(module).resolve("src/main/resources/flix.jetbrains.plugin.$module.xml")
        assertTrue("no module descriptor at $descriptor", descriptor.exists())
        return MODULE_DEPENDENCY.findAll(descriptor.readText()).map { it.groupValues[1] }.toSet()
    }

    /** Tests run from the module directory under Gradle, and from the repository root elsewhere. */
    private fun repositoryRoot(): Path {
        val cwd = Path.of("").toAbsolutePath()
        return if (cwd.fileName?.toString() in MODULES) cwd.parent else cwd
    }

    private fun <T> java.util.stream.Stream<T>.toList(): List<T> = collect(java.util.stream.Collectors.toList())

    private fun Sequence<String>.asStream(): java.util.stream.Stream<String> = java.util.stream.Stream.of(*toList().toTypedArray())

    private companion object {
        private val MODULE_DEPENDENCY = Regex("""<module\s+name="([^"]+)"\s*/>""")

        /** The content modules this plugin ships, each with a descriptor of its own. */
        private val MODULES = listOf("shared", "language", "backend", "debugger", "frontend")
    }
}
