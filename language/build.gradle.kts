import org.jetbrains.grammarkit.tasks.GenerateLexerTask
import org.jetbrains.grammarkit.tasks.GenerateParserTask
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// The Flix language layer: lexer, parser, PSI, editor support and the `def main` gutter marker,
// adopted from flix/intellij-flix. See NOTICE for the imported revision and docs/adr/0001 for why.
//
// Sources keep their original `org.flixlang.intellij` package names so the layer stays reviewable
// against, and re-synchronizable with, its origin.
plugins {
    id("org.jetbrains.grammarkit")
}

// Declared here rather than inherited from settings.gradle.kts: applying the Grammar-Kit plugin
// introduces project-level repositories, and Gradle then ignores the settings-level
// dependencyResolutionManagement block for this project -- which is where localPlatformArtifacts()
// lives, without which the local IDE artifact cannot be resolved.
// Pinned to a tagged flix-spec release, not the floating snapshot alias.
//
// A snapshot resolves to whatever was published most recently, so the fixture set can change
// underneath this repository with no commit here -- it went from 113 fixtures to 136 in a single
// afternoon while this was pinned to a timestamped snapshot build. Gradle also caches snapshots
// for 24 hours by default, so the same commit could resolve differently depending on the day.
// Either way a fixture-set change would surface as an apparent grammar regression.
//
// That is the same defect this test exists to retire -- FlixCorpusTest reads whichever Flix
// checkout happens to sit beside the repository -- so it must not be reintroduced one layer up.
// A release is immutable by flix-spec's own publish guard, which refuses to republish an existing
// version, so pinning one is the actual fix rather than a narrower version of the same problem.
//
// flix-spec's version is plain semver as of its v0.75.1 release: the Flix pin is no longer
// encoded in the coordinate (a version can advertise a pin but never enforce one), and is
// instead asserted directly in testPinMatchesLocalFlixCheckout via pin.json inside the artifact.
// Bumping this version is a reviewed change: a newer flix-spec release can carry a different Flix
// pin and therefore different expected trees.
val flixSpecVersion = "0.75.4"

repositories {
    mavenCentral()
    // flix-spec: the shared conformance fixtures, TreeKind/TokenKind inventories and projection
    // schemas, published as a Maven artifact from GitHub Pages. A versioned dependency rather than
    // a git submodule on purpose -- a floating submodule pointer across several CI configurations
    // defeats the pin that flix-spec exists to hold.
    maven {
        name = "flixSpec"
        url = uri("https://wstein.github.io/flix-spec/maven/")
        content { includeGroup("io.github.wstein") }
        // Pinned to a release version (see flixSpecVersion below), so only release coordinates
        // need to resolve here. snapshotsOnly() would refuse a release version outright.
        mavenContent { releasesOnly() }
    }
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Deliberately no bundledModule("intellij.platform.frontend"/"...backend"): a content module
        // that declares either is loaded in only that process. Everything here describes the
        // language itself -- file type, parser, highlighter, editor behaviour -- and has to be
        // visible wherever a .flix file is opened. This mirrors how the bundled TextMate and TOML
        // plugins split their own registrations.
        testFramework(TestFrameworkType.Platform)
    }

    // BasePlatformTestCase derives from JUnit 3-style junit.framework.TestCase; testFramework
    // supplies the IntelliJ fixtures but not JUnit itself.
    testImplementation("junit:junit:4.13.2")

    // Fixtures and inventories are consumed from the published artifact, pinned by version, so the
    // grammar is checked against a known revision of the Flix reference compiler rather than
    // against whichever Flix checkout happens to sit beside this repository.
    testImplementation("io.github.wstein:flix-spec:$flixSpecVersion")
}

// Grammar-Kit generates the lexer, parser and PSI from src/main/grammar into a build directory
// rather than a checked-in src/main/gen. Generated code is derived from the grammar and would
// otherwise be a second source of truth to keep in sync by hand.
val generatedSourcesDir = layout.buildDirectory.dir("generated/grammar")

val generateFlixParser = tasks.register<GenerateParserTask>("generateFlixParser") {
    sourceFile = file("src/main/grammar/Flix.bnf")
    targetRootOutputDir = generatedSourcesDir
    pathToParser = "org/flixlang/intellij/lang/parser/FlixParser.java"
    pathToPsiRoot = "org/flixlang/intellij/lang/psi"
    purgeOldFiles = true
}

val generateFlixLexer = tasks.register<GenerateLexerTask>("generateFlixLexer") {
    sourceFile = file("src/main/grammar/_Flix.flex")
    targetOutputDir = generatedSourcesDir.map { it.dir("org/flixlang/intellij/lang") }
    // Not purging: this task's output directory is a *parent* of the parser and PSI output, so
    // purging it deletes 352 of the 353 generated files and leaves a clean build dependent on task
    // ordering. The task emits exactly one deterministic file, which it overwrites in place.
    purgeOldFiles = false
    mustRunAfter(generateFlixParser)
}

sourceSets.main {
    java.srcDir(generatedSourcesDir)
}

tasks.compileKotlin { dependsOn(generateFlixParser, generateFlixLexer) }
tasks.compileJava { dependsOn(generateFlixParser, generateFlixLexer) }

tasks.test {
    useJUnit()

    // One JVM per test class, to isolate cross-class state leaking through the platform's test
    // fixtures.
    //
    // Three error-recovery assertions failed only in a full-suite run: passing alone, passing in
    // every pairwise combination, and failing once enough classes had run in one JVM. Parsing the
    // same sources directly showed the parser recovering correctly in all three, so the defect was
    // in the harness, not the grammar. ParsingTestCase builds a mock application and registers
    // extensions against static, per-Language collectors that outlive an individual class, and
    // FlixEditorBasicsRegistrationTest already has to clear those caches by hand for the same
    // reason.
    //
    // Forking costs about 20 seconds across the suite. That is worth paying not to have correct
    // code reported as broken depending on what ran before it.
    setForkEvery(1)

    // Lets FlixCorpusTest find the sibling Flix checkout that holds the parser corpus. It skips
    // when absent, so CI without the checkout still passes; see
    // docs/intellij-flix-parser-evaluation.md.
    //
    // Set only when non-empty. Unconditionally setting it to "" defeated `-DflixCorpusDir=...`,
    // because Gradle's empty value overwrote the one the user passed on the command line -- so the
    // documented invocation silently skipped the gate instead of running it.
    providers.gradleProperty("flixCorpusDir").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("flixCorpusDir", it) }

    // The pinned revision the grammar was derived from and validated against. FlixCorpusTest
    // verifies the checkout actually sits on it, so a gate run against a drifted corpus reports
    // that rather than quietly measuring something else.
    systemProperty("flixCorpusCommit", providers.gradleProperty("flixCorpusCommit").getOrElse(""))

    // Deliberate cross-version work is legitimate; silently accepting a mismatch is not. Forwarded
    // so the override has to be typed on the command line rather than defaulted into existence.
    providers.gradleProperty("flixSpec.allowPinMismatch").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("flixSpec.allowPinMismatch", it) }
}
