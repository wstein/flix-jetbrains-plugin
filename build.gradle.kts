import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

plugins {
    application
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.kotlin.jvm")
    id("rpc") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
    id("org.jetbrains.qodana")
}

subprojects {
    apply(plugin = "org.jetbrains.intellij.platform.module")
    apply(plugin = "rpc")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2026.1.3")

        pluginModule(implementation(project(":shared")))
        pluginModule(implementation(project(":language")))
        pluginModule(implementation(project(":debugger")))
        pluginModule(implementation(project(":frontend")))
        pluginModule(implementation(project(":backend")))

        testFramework(TestFrameworkType.Platform)
    }

    // Registration tests live here rather than in :language because they assert that the
    // *assembled* plugin wires the language layer up -- root plugin.xml <content> naming the
    // content module, which in turn declares the extensions. A content-module descriptor is inert
    // on its own, so a module-local test sandbox contains the jar but never reads its
    // <extensions>, and every extension lookup resolves null. Only the root project builds the
    // whole plugin, so only here can those lookups mean anything.
    //
    // Pure-parser tests stay in :language: ParsingTestCase registers the ParserDefinition
    // programmatically and needs no descriptor at all.
    testImplementation(project(":language"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

intellijPlatform {
    splitMode = true
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.BOTH
}

// See scripts/sync-debug-adapter.sh for what these actually do and why the vendored copy under
// backend/src/main/resources/dap/ needs this at all (two separate, non-remote-linked repos).
tasks.register<Exec>("checkDebugAdapterSync") {
    group = "verification"
    description = "Fails if backend/.../dap/FlixDebugAdapter.java has drifted from flix-lab's canonical copy."
    commandLine("scripts/sync-debug-adapter.sh", "--check")
}

tasks.register<Exec>("syncDebugAdapter") {
    group = "other"
    description = "Re-syncs backend/.../dap/FlixDebugAdapter.java from flix-lab's canonical copy."
    commandLine("scripts/sync-debug-adapter.sh")
}

// Deliberately NOT wired into the standard `check`/`build` lifecycle: it requires flix-lab as a
// sibling checkout, which won't exist for CI (no remote configured for either repo yet) or for
// anyone else cloning just this repo. Run `./gradlew checkDebugAdapterSync` manually.
