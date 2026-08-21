// Build logic compiled ahead of the main build.
//
// The integration-glue checker lives here rather than in an applied script because Gradle's
// configuration cache cannot serialize references to script objects: a `doLast` that calls a
// script-level function captures the whole script. As a compiled class it is just a class, and the
// tasks pass it plain Files.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    // Parses flix-integration.yaml. Build tooling only -- nothing that ships in the plugin needs it.
    implementation("org.yaml:snakeyaml:2.6")

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Run them whenever this build logic is rebuilt.
//
// buildSrc is a build of its own, so the root project cannot depend on a task in it, and Gradle does
// *not* run its tests for you: measured, an ordinary command builds `buildSrc:jar` and stops there.
// Hanging them off `jar` is what makes them run at all.
//
// What that does and does not cover is worth being exact about. Editing anything here invalidates
// the configuration cache, buildSrc is rebuilt, and the tests run -- which is the case that matters,
// since this code can only break by being edited. It is **not** a gate on anything outside buildSrc:
// on a configuration-cache hit buildSrc is not configured at all, so a test here that read some
// other file would never notice it change. `FlixReleaseWorkflowTest` lives in the root project's
// `check` for exactly that reason.
// `finalizedBy` rather than `dependsOn`, and that is not a style choice: the `kotlin-dsl` plugin
// puts this project's own jar on its test compile classpath, so a jar that depended on the tests
// would depend on compiling code that depends on the jar. Gradle reports it as a circular
// dependency. A finalizer runs after the jar is built and still fails the invocation.
tasks.named("jar") {
    finalizedBy(tasks.test)
}
