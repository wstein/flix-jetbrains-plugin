plugins { id("io.github.wstein.flix-spec") version "0.79.0-20261109.125843-3" }

repositories {
    ivy {
        name = "qualifiedFlixSpec079"
        url = uri("https://wstein.github.io/flix-spec/maven/io/github/wstein/")
        patternLayout { artifact("[artifact]/0.79.0-SNAPSHOT/[artifact]-[revision].[ext]") }
        metadataSources { artifact() }
        content { includeGroup("io.github.wstein") }
    }
}

flixSpec {
    runnerVersion.set("0.79.0-20261109.125843-3")
    // Exact timestamped artifact bytes are independently SHA-256 verified.
    specVersion.set("0.79.0-20261109.125843-3")
    actualDirectory.set(layout.projectDirectory.dir("../language/build/flix-spec-projection"))
    projectionMap.set(layout.projectDirectory.file("../language/src/test/resources/conformance/projection-map.json"))
    baseline.set(3) // Reviewed pre-existing differences; docs/conformance-migration.md.
    recoveryBaseline.set(2) // Reviewed identities, not a free allowance; see the accepted file.
    depthFloor.set(63)
    recoveryDepthFloor.set(67)
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
tasks.named("flixSpecCheck") { dependsOn(emitAdapterProjections) }
