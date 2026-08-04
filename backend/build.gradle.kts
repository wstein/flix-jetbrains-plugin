import org.jetbrains.intellij.platform.gradle.TestFrameworkType

dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.rpc.backend")
        bundledModule("intellij.platform.backend")

        // LSP4IJ provides the generic LSP client machinery this plugin builds on. Its DAP client
        // is deliberately unused: debugging goes through IntelliJ's own JVM debugger (ADR 0002),
        // which is what lets one session cover Flix and every other JVM language. Version matches what was verified
        // working against 2025.2.6.2 in the single-module prototype this was ported from; LSP4IJ's
        // declared compatibility range (242+, no upper bound) should still resolve fine against
        // 2026.1.3, but that combination hasn't been build/run-verified yet.
        plugin("com.redhat.devtools.lsp4ij", "0.20.1")


        // The root project's testFramework(Platform) declaration doesn't propagate to this
        // module's own test source set -- needed here directly for FlixForkTest's
        // BasePlatformTestCase fixture.
        testFramework(TestFrameworkType.Platform)
    }

    // BasePlatformTestCase extends JUnit 3-style junit.framework.TestCase; testFramework(Platform)
    // provides the IntelliJ test fixtures but not JUnit itself.
    testImplementation("junit:junit:4.13.2")

    implementation(project(":shared"))

    // The language module owns what both halves of the plugin share below the LSP layer: the Flix
    // settings that the server's command line and every task read, and the Language this module
    // names by id in its descriptor. The descriptor has always declared this dependency; it is now
    // a compile-time one too, because a second copy of the settings service is exactly the kind of
    // duplication that lets the editor and a task disagree about what the user configured.
    implementation(project(":language"))
}
