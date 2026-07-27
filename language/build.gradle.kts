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
repositories {
    mavenCentral()
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
    // Lets FlixCorpusTest find the sibling Flix checkout that holds the parser corpus. It skips
    // when absent, so CI without the checkout still passes; see
    // docs/intellij-flix-parser-evaluation.md.
    systemProperty("flixCorpusDir", providers.gradleProperty("flixCorpusDir").getOrElse(""))
}
