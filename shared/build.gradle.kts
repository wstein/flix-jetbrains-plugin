// Cross-boundary code with no IntelliJ Platform dependency: it is loaded in every process and
// must stay loadable wherever the plugin is. FlixLaunchCommand lives here because the CodeLens
// action (backend) and the native JVM debug configuration (debugger) need the identical Flix
// invocation, and its ordering rules are subtle enough that a second copy would drift.
dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()

    // FlixTaskTest checks its subcommand names against the compiler's own declaration of them, and
    // the Flix checkout is where that lives. The same property the corpus gate uses, so one setting
    // serves both: CI already clones flix/flix at `flixCorpusCommit` and passes it here.
    //
    // Set only when non-empty. Passing "" unconditionally would overwrite a value given on the
    // command line, which is how the corpus gate once silently skipped instead of running.
    providers.gradleProperty("flixCorpusDir").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("flixCorpusDir", it) }
}
