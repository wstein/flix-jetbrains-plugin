import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// Flix support for IntelliJ's own JVM debugger (ADR 0002). Kept in its own content module because
// com.intellij.debugger.PositionManagerFactory lives in the Java plugin: scoping the dependency
// here means an IDE without Java support loses only the debugger, not the language layer.
dependencies {
    intellijPlatform {
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }

    implementation(project(":language"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
