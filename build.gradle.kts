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

    // `FLIX_JAR` is the documented way to point the plugin at a compiler, so a machine that exports
    // it silently changes what several tests assert -- `FlixForkTest` and `FlixRunConfigurationTest`
    // both pin what happens when *no* compiler can be found, and both passed for the opposite
    // reason on a developer's shell. The absence is the fixture; it is removed here rather than
    // worked around in each test.
    //
    // `debugger` is the exception and keeps it: its live session compiles and launches a real
    // program, so without a compiler there is nothing to run and the test skips.
    if (name != "debugger") {
        tasks.withType<Test>().configureEach { environment.remove("FLIX_JAR") }
    }
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

    // Reads .github/workflows/release.yml, so that the two release channels cannot quietly merge.
    // Test-scoped: nothing that ships parses YAML.
    testImplementation("org.yaml:snakeyaml:2.6")

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

    // See the `subprojects` block: the absence of a compiler is a fixture, not a property of the
    // developer's shell. This is the same removal for the assembled-plugin tests.
    environment.remove("FLIX_JAR")
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareTestSandbox") {
    // IU bundles the Vue plugin, whose LSP activation rule fails to initialise in a headless test
    // IDE. `LspOpenedFilesService` enumerates every `LspServerSupportProvider` when a file is
    // opened, so any BasePlatformTestCase triggers it; the failure is logged on a pooled thread and
    // `TestLoggerFactory` turns a logged error into a failure of whichever test happens to be
    // running. That is why it landed on a different test each time and why rerunning the named test
    // alone passed.
    //
    // Nothing in this plugin touches the platform LSP API -- it uses LSP4IJ (ADR 0001) -- so the
    // Vue plugin has no business being loaded here at all. Disabling it in the *test* sandbox is
    // the platform's own mechanism (`disabled_plugins.txt`), and it is scoped to that sandbox: the
    // IDEs launched by `runIde` and `testIdeUi` are untouched, because a developer looking at a
    // real IDE should see the real set of plugins.
    //
    // `FlixTestSandboxTest` asserts the plugin is actually absent, since a plugin id that stops
    // matching would leave this silently doing nothing.
    disabledPlugins.add("org.jetbrains.plugins.vue")
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
// Scoped to `de.wstein.flixplugin` -- only this plugin's own categories, and only in a development
// sandbox. `LOG.isDebugEnabled` still guards the expensive call sites, so an unused category costs
// a branch.
//
// Read the output with:
//   tail -f .intellijPlatform/sandbox/*/IU-*/system*/log/idea.log | grep de.wstein
listOf("runIde", "runIdeSplitMode", "runIdeBackend", "runIdeFrontend").forEach { name ->
    tasks.matching { it.name == name }.configureEach {
        (this as? JavaForkOptions)?.systemProperty(
            "idea.log.debug.categories",
            "#de.wstein.flixplugin.debugger,#de.wstein.flixplugin.run",
        )
    }
}

// Phase 5: the cross-module wiring contract and its checker.
apply(from = "gradle/integration-glue.gradle.kts")

// Signing credentials, when a release workflow supplies them as files.
//
// Files rather than the `CERTIFICATE_CHAIN` / `PRIVATE_KEY` environment variables the plugin also
// accepts, and that is measured rather than preference: with the chain supplied as *content*,
// `verifyPluginSignature` passes the PEM itself to the ZIP signer as an argument and dies with
// `Invalid argument: -----BEGIN CERTIFICATE-----`. `signPlugin` accepts either. Files are the form
// both tasks agree on.
//
// Absent, the properties are simply not set: `signPlugin` is then SKIPPED, which is right for an
// ordinary local build and is why the release job checks that a signed archive actually appeared.
intellijPlatform {
    signing {
        providers.gradleProperty("certificateChainFile").orNull?.let {
            certificateChainFile = layout.projectDirectory.file(it)
        }
        providers.gradleProperty("privateKeyFile").orNull?.let {
            privateKeyFile = layout.projectDirectory.file(it)
        }
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
}

// Verifying a signature is a statement about the archive `signPlugin` produces, and the plugin does
// not say so itself: asking for both in one invocation fails with "Task ':verifyPluginSignature'
// uses this output of task ':signPlugin' without declaring an explicit or implicit dependency", and
// Gradle schedules the verification *first*, where it finds no file and reports NO-SOURCE. Measured,
// with a throwaway key -- the release workflow's signing step would have failed on its first run.
tasks.named("verifyPluginSignature") {
    dependsOn(tasks.named("signPlugin"))
}

// The opt-in preview channel's feed. Marketplace stays the stable one.
apply(from = "gradle/beta-plugin-repo.gradle.kts")

// `FlixReleaseWorkflowTest` reads the release workflow, which no Gradle project owns and which
// Gradle therefore cannot know is an input. Undeclared, the test task stays up to date across every
// edit to the workflow, so the channel contract would be checked once and never again -- measured,
// as three mutations of the workflow that all passed.
tasks.test {
    inputs.file(layout.projectDirectory.file(".github/workflows/release.yml"))
        .withPropertyName("releaseWorkflow")
}
