package dev.wstein.flixplugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The README's badges say something true.
 *
 * A badge is a claim rendered as a fact. The two dynamic ones cannot lie -- GitHub renders the real
 * workflow result -- but they *can* point at a workflow that no longer exists, and a badge for a
 * missing workflow renders as "no status", which reads like a passing build that has not run yet.
 *
 * The static ones can lie outright. `IntelliJ Platform 2026.1.3` is a second copy of the version in
 * `build.gradle.kts`: bumping the platform without touching the README leaves a badge advertising an
 * IDE this plugin is no longer built against. That is the drift this repository already refuses
 * elsewhere, so it is checked here rather than remembered.
 *
 * Deliberately not a badge, and therefore not checked here: JetBrains Marketplace. The plugin is
 * sideloaded and has no listing, so a version or download badge would render an error and imply the
 * opposite.
 */
class FlixReadmeBadgesTest {

    private val readme = File("README.md").readText()

    @Test
    fun `every workflow a badge points at exists`() {
        val referenced = WORKFLOW_BADGE.findAll(readme).map { it.groupValues[1] }.toList()
        assertTrue("the README has no workflow badges at all", referenced.isNotEmpty())

        referenced.forEach { workflow ->
            assertTrue(
                "a badge points at .github/workflows/$workflow, which does not exist; " +
                    "GitHub renders that as 'no status', which reads like a build that has not failed",
                File(".github/workflows/$workflow").isFile,
            )
        }
    }

    @Test
    fun `the platform badge names the platform actually built against`() {
        val declared = PLATFORM_IN_BUILD.find(File("build.gradle.kts").readText())?.groupValues?.get(1)
        assertTrue("build.gradle.kts no longer declares intellijIdea(\"…\")", declared != null)

        val advertised = PLATFORM_BADGE.find(readme)?.groupValues?.get(1)
        assertEquals(
            "the README advertises an IntelliJ Platform version this plugin is not built against",
            declared,
            advertised,
        )
    }

    @Test
    fun `the license badge names the licence that is actually shipped`() {
        assertTrue("the badge links to LICENSE, which does not exist", File("LICENSE").isFile)
        assertTrue(
            "the badge says Apache-2.0 but LICENSE does not",
            File("LICENSE").readText().contains("Apache License"),
        )
        assertTrue("the README lost its licence badge", readme.contains("license-Apache--2.0"))
    }

    private companion object {
        /** `…/actions/workflows/<file>/badge.svg` */
        val WORKFLOW_BADGE = Regex("""actions/workflows/([\w.-]+)/badge\.svg""")

        /** `IntelliJ%20Platform-<version>-` in the shields.io path. */
        val PLATFORM_BADGE = Regex("""IntelliJ%20Platform-([\d.]+)-""")

        /** `intellijIdea("<version>")` in the root build script. */
        val PLATFORM_IN_BUILD = Regex("""intellijIdea\("([\d.]+)"\)""")
    }
}
