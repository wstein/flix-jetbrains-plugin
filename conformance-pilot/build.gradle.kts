plugins { id("io.github.wstein.flix-spec") version "0.77.4" }

repositories {
    maven(providers.gradleProperty("flixSpec.pilotRepository").get())
    maven("https://wstein.github.io/flix-spec/maven")
}

flixSpec {
    runnerVersion.set("0.77.4")
    // Preserve the real consumer's existing pin; this pilot does not migrate its grammar.
    specVersion.set("0.75.8")
    actualDirectory.set(layout.projectDirectory.dir("../language/build/flix-spec-projection"))
    projectionMap.set(layout.projectDirectory.file("../language/src/test/resources/conformance/projection-map.json"))
    baseline.set(4) // The existing Kotlin comparator's DIVERGENCE_BASELINE, not a new allowance.
}

// Regenerate using the actual IntelliJ parser, even if the previous test run was up-to-date.
// No outputs are declared here: this pilot deliberately never reuses adapter output.
val pilotBundle = tasks.register<Copy>("pilotBundle") {
    from(configurations.named("flixSpecBundle"))
    into(layout.buildDirectory.dir("pilot-input"))
    rename { "spec.jar" }
}
val emitAdapterProjections = tasks.register<Exec>("emitAdapterProjections") {
    dependsOn("prepareFlixSpec", pilotBundle)
    workingDir(layout.projectDirectory.dir(".."))
    commandLine("./gradlew", ":language:test", "--tests",
        "org.flixlang.intellij.lang.FlixSpecConformanceTest.testFixturesParseAndProject", "--rerun",
        "-PflixSpec.pilotBundle=${layout.buildDirectory.file("pilot-input/spec.jar").get().asFile}")
}
tasks.named("flixSpecCheck") { dependsOn(emitAdapterProjections) }
