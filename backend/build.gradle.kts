import org.jetbrains.intellij.platform.gradle.TestFrameworkType

dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.rpc.backend")
        bundledModule("intellij.platform.backend")

        // LSP4IJ provides the generic LSP + DAP client machinery this plugin builds on -- see
        // README.md for why (native platform LSP support has no DAP equivalent yet, LSP4IJ's DAP
        // client is the only generic path into IntelliJ today). Version matches what was verified
        // working against 2025.2.6.2 in the single-module prototype this was ported from; LSP4IJ's
        // declared compatibility range (242+, no upper bound) should still resolve fine against
        // 2026.1.3, but that combination hasn't been build/run-verified yet.
        plugin("com.redhat.devtools.lsp4ij", "0.20.1")

        // Bundled with the platform itself (not a Marketplace plugin) -- needed on the compile
        // classpath for FlixTextMateBundleProvider, which registers the bundled fallback grammar
        // via the real com.intellij.textmate.bundleProvider extension point.
        bundledPlugin("org.jetbrains.plugins.textmate")

        // The root project's testFramework(Platform) declaration doesn't propagate to this
        // module's own test source set -- needed here directly for FlixForkTest's
        // BasePlatformTestCase fixture.
        testFramework(TestFrameworkType.Platform)
    }

    // BasePlatformTestCase extends JUnit 3-style junit.framework.TestCase; testFramework(Platform)
    // provides the IntelliJ test fixtures but not JUnit itself.
    testImplementation("junit:junit:4.13.2")

    implementation(project(":shared"))
}
