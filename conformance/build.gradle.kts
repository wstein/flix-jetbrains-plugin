plugins { id("io.github.wstein.flix-spec") version "0.77.4" }

repositories {
    providers.gradleProperty("flixSpecRepository").orNull?.let { maven(it) }
    maven("https://wstein.github.io/flix-spec/maven")
}

flixSpec {
    runnerVersion.set("0.77.4")
    // Preserve the real consumer's existing pin; this pilot does not migrate its grammar.
    specVersion.set("0.75.8")
    actualDirectory.set(layout.projectDirectory.dir("../language/build/flix-spec-projection"))
    projectionMap.set(layout.projectDirectory.file("../language/src/test/resources/conformance/projection-map.json"))
    baseline.set(4) // Reviewed pre-existing differences; docs/conformance-migration.md.
    recoveryBaseline.set(6) // Reviewed identities, not a free allowance; see the accepted file.
    depthFloor.set(36)
    recoveryDepthFloor.set(40)
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
