// Phase 5: the cross-module wiring contract and its checker.
//
// The logic lives in buildSrc/src/main/kotlin/IntegrationGlue.kt -- including why the module
// descriptors are verified rather than generated. These tasks only wire it up.
//
// Every value a task action touches is a local of the configuration block, never a script-level
// property. Gradle's configuration cache cannot serialize a reference to the script object, and a
// top-level `val` in an applied script *is* a property of it -- so closing over one fails the build
// with a message that names the cache rather than the capture.

tasks.register("generateIntegrationGlue") {
    val root = rootProject.projectDir
    val matrix = IntegrationGlue.matrixOf(root)

    group = "build"
    description = "Regenerates docs/flix-integration-matrix.md from flix-integration.yaml."
    inputs.file(IntegrationGlue.manifestOf(root))
    outputs.file(matrix)

    doLast {
        matrix.parentFile.mkdirs()
        matrix.writeText(IntegrationGlue.renderMatrix(root))
        logger.lifecycle("Wrote ${matrix.relativeTo(root)}")
    }
}

tasks.register("checkIntegrationGlue") {
    val root = rootProject.projectDir
    val matrix = IntegrationGlue.matrixOf(root)

    group = "verification"
    description = "Fails if the committed registrations disagree with flix-integration.yaml."
    inputs.file(IntegrationGlue.manifestOf(root))
    inputs.files(IntegrationGlue.MODULES.map { IntegrationGlue.descriptorOf(root, it) }.filter { it.exists() })

    doLast {
        val problems = IntegrationGlue.verify(root).toMutableList()

        // The matrix is committed, so a stale one is a review-visible diff rather than something
        // noticed only when the next person happens to regenerate it.
        if (!matrix.exists() || matrix.readText() != IntegrationGlue.renderMatrix(root)) {
            problems += "docs/flix-integration-matrix.md is stale -- run ./gradlew generateIntegrationGlue"
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Integration glue does not match flix-integration.yaml:")
                    problems.forEach { appendLine("  - $it") }
                    appendLine()
                    append("Fix the registration, or update the manifest if the change was intended.")
                },
            )
        }
        logger.lifecycle("Integration glue matches flix-integration.yaml.")
    }
}

tasks.named("check") { dependsOn("checkIntegrationGlue") }
