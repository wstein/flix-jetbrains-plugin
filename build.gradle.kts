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

// UI smoke test -- see src/integrationTest and docs/phase-8-verification.md.
//
// Its own source set and its own task, deliberately outside `check`. This is not a headless test:
// it launches a real IDE and drives real Swing components through an AWT robot, which on macOS
// takes over the cursor and on Linux needs xvfb plus a window manager. A suite that ran it by
// default would be a suite people stop running.
//
// Declared before `dependencies` because that block names `integrationTestImplementation`, and a
// source set's configurations do not exist until it does.
//
// Run with: ./gradlew testIdeUi
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

// The LSP4IJ distribution, resolved so the UI test can install it into the IDE Starter builds.
//
// Starter creates its own IDE installation under `out/ide-tests` and ignores the Gradle sandbox, so
// `testIdeUi { plugins {} }` cannot supply it. Downloading from the Marketplace at test time was the
// obvious alternative and fails: 0.20.1 has no build-261 artifact to fetch, so the request 404s.
// Taking the same artifact the plugin is compiled against removes both the network and the chance
// of testing against a different version than the one shipped.
val lsp4ijDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
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

        // Starter drives the UI smoke test. Its version is not written by hand: the plugin resolves
        // it from the platform's own build number, so the driver can never drift from the IDE it
        // drives -- which is the failure that makes a UI test flake rather than fail.
        testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")

        // Test-scoped only. Root-plugin integration tests need to name debugger APIs
        // (SourcePosition, DebugProcess) to drive FlixPositionManager against the assembled plugin,
        // where `.flix` actually resolves to the Flix file type. `testBundledPlugin` rather than
        // `bundledPlugin` because the latter would make the *shipped* plugin depend on the Java
        // plugin, and ADR 0001 requires the language layer to keep loading in IDEs without one --
        // that dependency belongs to the debugger content module alone.
        testBundledPlugin("com.intellij.java")
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

    // The UI smoke test drives a real IDE, so it needs JUnit 5 and Starter's own transitive
    // surface: the API returns Kodein `DI` and coroutine `Deferred` in public signatures.
    integrationTestImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    integrationTestImplementation("org.kodein.di:kodein-di-jvm:7.20.2")
    integrationTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")

    // Gradle needs the launcher on the *runtime* classpath to start a JUnit 5 task at all; without
    // it the task fails before loading a single test, with a message about the platform rather than
    // about the missing artifact.
    "integrationTestRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.11.4")

    lsp4ijDistribution("com.jetbrains.plugins:com.redhat.devtools.lsp4ij:0.20.1@zip")

    // `kotlin.stdlib.default.dependency = false` keeps the stdlib out of the shipped plugin, which
    // is correct there -- the platform provides it. Here it is not: Starter reaches Kodein, Kodein
    // reaches kotlin-reflect, and reflect against an older stdlib dies on a missing internal class
    // (`KotlinGenericDeclaration`) with a stack trace naming neither. Pinned together on purpose.
    integrationTestImplementation(kotlin("stdlib"))
    integrationTestImplementation(kotlin("reflect"))
}

tasks.test {
    useJUnit()
}

val testIdeUi by intellijPlatformTesting.testIdeUi.registering {
    // Split mode is how this plugin actually ships, but the driver selects a different runner for a
    // frontend/backend pair and that path is unproven here. The gutter and breakpoint gestures being
    // asserted are frontend-side either way, so the single-process run tests the same gestures
    // without also testing the harness. Split-mode UI coverage stays an open question, recorded as
    // such rather than half-configured.
    splitMode = false

    task {
        useJUnitPlatform()
        testClassesDirs = sourceSets["integrationTest"].output.classesDirs
        classpath = sourceSets["integrationTest"].runtimeClasspath

        // Inherited so the test can resolve a compiler jar the same way the plugin does.
        environment("FLIX_JAR", providers.environmentVariable("FLIX_JAR").getOrElse(""))

        // Resolved inside the provider rather than at configuration time, so the configuration
        // cache is not asked to serialize a resolved artifact set.
        val lsp4ij = lsp4ijDistribution.elements.map { it.first().asFile.absolutePath }
        jvmArgumentProviders.add(
            CommandLineArgumentProvider { listOf("-Dpath.to.lsp4ij=${lsp4ij.get()}") },
        )
    }
}

intellijPlatform {
    splitMode = true
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.BOTH

    // Which IDEs the Plugin Verifier checks the compiled bytecode against.
    //
    // Not optional. `verifyPlugin` runs in CI on every push and pull request, but with no IDE
    // targets it has nothing to compare against and reports success without checking anything --
    // the same shape of vacuous gate this project has been caught by before, and the reason the
    // corpus test now fails rather than skips under CI.
    //
    // `recommended()` covers the release and EAP builds JetBrains suggests for the declared
    // compatibility range, which is what catches an accidental internal-API call before it ships
    // rather than after a user installs it.
    pluginVerification {
        ides {
            recommended()
        }
    }
}

// Turns on this plugin's own debug logging in every sandbox IDE.
//
// The alternative is Help > Diagnostic Tools > Debug Log Settings, which is easy to get wrong here:
// under split mode the debugger extensions run backend-side, so the category has to be set in the
// backend process, and the setting lives in sandbox state that a clean wipes. A system property is
// deterministic and survives both.
//
// Scoped to `dev.wstein.flixplugin` -- only this plugin's own categories, and only in a development
// sandbox. `LOG.isDebugEnabled` still guards the expensive call sites, so an unused category costs
// a branch.
//
// Read the output with:
//   tail -f .intellijPlatform/sandbox/*/IU-*/system*/log/idea.log | grep dev.wstein
listOf("runIde", "runIdeSplitMode", "runIdeBackend", "runIdeFrontend").forEach { name ->
    tasks.matching { it.name == name }.configureEach {
        (this as? JavaForkOptions)?.systemProperty(
            "idea.log.debug.categories",
            "#dev.wstein.flixplugin.debugger,#dev.wstein.flixplugin.run",
        )
    }
}

// Phase 5: the cross-module wiring contract and its checker.
apply(from = "gradle/integration-glue.gradle.kts")
