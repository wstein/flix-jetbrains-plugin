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
    implementation("org.yaml:snakeyaml:2.3")
}
