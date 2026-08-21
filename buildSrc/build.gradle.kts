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

// Run them on every invocation, not only under `check`.
//
// buildSrc is a build of its own, so the root project cannot depend on a task in it, and Gradle
// does *not* run its tests for you: measured, an ordinary command builds `buildSrc:jar` and stops
// there. Hanging them off `jar` is what makes them run at all, and running them always is right for
// what lives here -- a checker whose whole job is to fail the build, and a release feed whose
// generator must not be reachable while broken. They are milliseconds; a broken one costs a
// published artifact nobody can install.
// `finalizedBy` rather than `dependsOn`, and that is not a style choice: the `kotlin-dsl` plugin
// puts this project's own jar on its test compile classpath, so a jar that depended on the tests
// would depend on compiling code that depends on the jar. Gradle reports it as a circular
// dependency. A finalizer runs after the jar is built and still fails the invocation.
tasks.named("jar") {
    finalizedBy(tasks.test)
}
