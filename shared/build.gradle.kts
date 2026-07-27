// Cross-boundary code with no IntelliJ Platform dependency: it is loaded in every process and
// must stay loadable wherever the plugin is. FlixLaunchCommand lives here because the CodeLens
// action (backend) and the native JVM debug configuration (debugger) need the identical Flix
// invocation, and its ordering rules are subtle enough that a second copy would drift.
dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
