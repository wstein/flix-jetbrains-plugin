import flix.spec.gradle.FlixSpecCheck
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins { id("io.github.wstein.flix-spec") version "0.80.0-20261115.222714-1" }

repositories {
    ivy {
        name = "qualifiedFlixSpec080"
        url = uri("https://wstein.github.io/flix-spec/maven/io/github/wstein/")
        patternLayout { artifact("[artifact]/0.80.0-SNAPSHOT/[artifact]-[revision].[ext]") }
        metadataSources { artifact() }
        content { includeGroup("io.github.wstein") }
    }
}

flixSpec {
    runnerVersion.set("0.80.0-20261115.222714-1")
    // Exact timestamped artifact bytes are independently SHA-256 verified.
    specVersion.set("0.80.0-20261115.222714-1")
    actualDirectory.set(layout.projectDirectory.dir("../language/build/flix-spec-projection"))
    projectionMap.set(layout.projectDirectory.file("../language/src/test/resources/conformance/projection-map.json"))
    baseline.set(4) // Three historical identities plus the reserved-val recovery difference.
    recoveryBaseline.set(4) // Two historical identities plus two reserved-val identities.
    depthFloor.set(63)
    recoveryDepthFloor.set(65) // Full suite; the 167-fixture gate below retains 67%.
    accepted.set(layout.projectDirectory.file("../language/src/test/resources/conformance/accepted.json"))
}

// Regenerate using the actual IntelliJ parser, even if the previous test run was up-to-date.
// No outputs are declared here: the gate deliberately never reuses adapter output.
val pilotBundle = tasks.register<Copy>("pilotBundle") {
    from(configurations.named("flixSpecBundle"))
    into(layout.buildDirectory.dir("pilot-input"))
    rename { "spec.jar" }
}
val emitAdapterProjections = tasks.register<Exec>("emitAdapterProjections") {
    dependsOn("prepareFlixSpec", pilotBundle)
    workingDir(layout.projectDirectory.dir(".."))
    commandLine("./gradlew", ":language:test", "--tests",
        "org.flixlang.intellij.lang.FlixSpecConformanceTest.testFixturesParseAndProject",
        "--tests", "org.flixlang.intellij.lang.FlixSpecConformanceTest.testProjection*", "--rerun",
        "-PflixSpec.pilotBundle=${layout.buildDirectory.file("pilot-input/spec.jar").get().asFile}")
}
// The full revision is bound by accepted.json. Independently retain the original
// floors and identities on all fixtures except the one approved negative recovery case.
val historicalSpec = tasks.register<Sync>("prepareHistoricalSpec") {
    dependsOn("prepareFlixSpec")
    from(layout.buildDirectory.dir("flix-spec/spec")) {
        exclude("fixtures/expected/identifier__reserved-val.json", "fixtures/raw/identifier__reserved-val.json")
    }
    into(layout.buildDirectory.dir("flix-spec/historical"))
}
val toolchains = extensions.getByType<JavaToolchainService>()
val historicalCheck = tasks.register<FlixSpecCheck>("historicalFlixSpecCheck") {
    dependsOn(historicalSpec, emitAdapterProjections)
    runnerClasspath.from(configurations.named("flixSpecRunner"))
    specRoot.set(layout.buildDirectory.dir("flix-spec/historical"))
    sourceRoot.set(specRoot)
    sourceFiles.from(specRoot.asFileTree.matching { include("**/*.flix") })
    actualDirectory.set(layout.projectDirectory.dir("../language/build/flix-spec-projection"))
    projectionMap.set(layout.projectDirectory.file("../language/src/test/resources/conformance/projection-map.json"))
    accepted.set(layout.projectDirectory.file("../language/src/test/resources/conformance/accepted-historical.json"))
    baseline.set(3)
    recoveryBaseline.set(2)
    diagnosticBaseline.set(0)
    depthFloor.set(63)
    recoveryDepthFloor.set(67)
    javaLauncher.set(toolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    jsonReport.set(layout.buildDirectory.file("reports/flix-spec/historical.json"))
    htmlReport.set(layout.buildDirectory.file("reports/flix-spec/historical.html"))
    outputs.upToDateWhen { false }
}
tasks.named("flixSpecCheck") {
    dependsOn(emitAdapterProjections, historicalCheck)
    outputs.upToDateWhen { false }
}
