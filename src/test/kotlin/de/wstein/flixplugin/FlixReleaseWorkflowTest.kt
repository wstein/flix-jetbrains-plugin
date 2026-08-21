package de.wstein.flixplugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * That the two release channels stay separate.
 *
 * ## Why a test rather than a review
 *
 * The channel contract is one `if:` per job and one secret list per job, and both are the kind of
 * line that survives an edit intended for something else. What they prevent is not a broken build:
 * a prerelease that reaches `publishPlugin` succeeds, and ships a beta to every Marketplace user of
 * the stable channel. There is no undo for that, so it is checked mechanically.
 *
 * ## What it reads
 *
 * The workflow file itself, as YAML. Asserting on the parsed structure rather than on the text means
 * a job renamed or a step reordered still has to satisfy the same claims.
 *
 * ## Why here rather than in buildSrc
 *
 * It reads a file no Gradle project owns, and buildSrc's tests are reached through a finalizer on
 * its jar -- which the configuration cache skips entirely on a hit. Measured: three mutations of the
 * workflow all passed there, because nothing re-ran. Under the root project's `check` it is an
 * ordinary test, with the workflow declared as an input of the test task so that editing it is
 * enough to re-run.
 */
class FlixReleaseWorkflowTest {

    private val workflow: Map<*, *> = Yaml().load(releaseWorkflowFile().readText())

    private fun releaseWorkflowFile(): File {
        // Walked up from the working directory rather than named relative to it, so the test does
        // not depend on which directory the build happens to run tests from.
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val candidate = File(directory, ".github/workflows/release.yml")
            if (candidate.isFile) {
                return candidate
            }
            directory = directory.parentFile
        }
        throw AssertionError("no .github/workflows/release.yml above ${System.getProperty("user.dir")}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun jobs(): Map<String, Map<String, Any?>> = workflow["jobs"] as Map<String, Map<String, Any?>>

    private fun job(name: String): Map<String, Any?> =
        jobs()[name] ?: throw AssertionError("no `$name` job; the workflow has ${jobs().keys}")

    @Suppress("UNCHECKED_CAST")
    private fun steps(name: String): List<Map<String, Any?>> =
        (job(name)["steps"] as? List<Map<String, Any?>>).orEmpty()

    /** Everything a job's steps would run or use, as one string. */
    private fun body(name: String): String = steps(name).joinToString("\n") { step ->
        listOfNotNull(step["run"], step["uses"], step["with"], step["env"]).joinToString("\n")
    }

    @Test
    fun `both release events still reach this workflow`() {
        // Looked up under `true` as well as `"on"`. YAML 1.1 resolves the bare word `on` to a
        // boolean, so a parser following that version -- SnakeYAML does -- keys this map by `true`.
        // GitHub reads it as the trigger regardless; only the test has to know.
        val on = (workflow["on"] ?: workflow[true]) as Map<*, *>
        val types = (on["release"] as Map<*, *>)["types"] as List<*>

        assertEquals(setOf("prereleased", "released"), types.toSet())
    }

    @Test
    fun `the stable job runs only for a published release`() {
        assertEquals("github.event.action == 'released'", job("release")["if"])
    }

    @Test
    fun `the beta job runs only for a prerelease`() {
        assertEquals("github.event.action == 'prereleased'", job("beta")["if"])
    }

    @Test
    fun `a prerelease never invokes publishPlugin`() {
        // The claim the whole split exists for. `publishPlugin` uploads to JetBrains Marketplace,
        // which is the stable channel; a beta arriving there reaches every user of the plugin.
        assertFalse("the beta job runs publishPlugin: ${body("beta")}", body("beta").contains("publishPlugin"))
        assertFalse(
            "the beta feed's deploy job runs publishPlugin",
            body("beta-pages").contains("publishPlugin"),
        )
    }

    @Test
    fun `a prerelease is never given the Marketplace token`() {
        // Belt as well as braces, and not redundant: the previous test is about a task name, and a
        // token that is present is a token a future step can use. Absent, no step in this job can
        // publish however it is written.
        assertFalse("the beta job is given PUBLISH_TOKEN", body("beta").contains("PUBLISH_TOKEN"))
        assertFalse("the deploy job is given PUBLISH_TOKEN", body("beta-pages").contains("PUBLISH_TOKEN"))
    }

    @Test
    fun `a prerelease is signed`() {
        // The feed advertises a plugin an IDE will install. Unsigned is a different thing to ship.
        assertTrue("the beta job does not sign: ${body("beta")}", body("beta").contains("signPlugin"))
        assertTrue(
            "the beta job does not verify the signature",
            body("beta").contains("verifyPluginSignature"),
        )
    }

    @Test
    fun `a stable release does not touch the beta feed`() {
        // The other direction of the same contract. A stable release publishing to the feed would
        // offer beta subscribers a build the channel does not carry, and -- worse -- would leave the
        // feed advertising it until the next prerelease.
        val stable = body("release")

        assertFalse("the stable job generates the feed: $stable", stable.contains("generateBetaPluginRepo"))
        assertFalse("the stable job uploads a Pages artifact: $stable", stable.contains("upload-pages-artifact"))
        assertFalse("the stable job deploys Pages: $stable", stable.contains("deploy-pages"))
    }

    @Test
    fun `only the deploy job may write to Pages`() {
        // Split so that the credentials that can change what every beta user downloads are held by a
        // job that builds nothing and runs only after every check has passed.
        @Suppress("UNCHECKED_CAST")
        fun permissions(name: String) = (job(name)["permissions"] as? Map<String, Any?>).orEmpty()

        assertFalse("the beta build job may write Pages", permissions("beta").containsKey("pages"))
        assertFalse("the stable job may write Pages", permissions("release").containsKey("pages"))
        assertEquals("write", permissions("beta-pages")["pages"])
        // Pages deployment authenticates with OIDC rather than a token.
        assertEquals("write", permissions("beta-pages")["id-token"])
    }

    @Test
    fun `the feed is deployed only after the artifact has been verified`() {
        assertEquals("beta", job("beta-pages")["needs"])

        val names = steps("beta").map { it["name"] as? String ?: "" }
        val verified = names.indexOfFirst { it.contains("Verify The Published Asset") }
        val generated = names.indexOfFirst { it.contains("Generate Beta Plugin Repository") }
        val validated = names.indexOfFirst { it.contains("Validate The Feed") }
        val uploaded = names.indexOfFirst { it.contains("Upload Pages Artifact") }

        assertTrue("the published asset is never verified: $names", verified >= 0)
        assertTrue("the feed is generated before the asset is verified", generated > verified)
        assertTrue("the feed is uploaded before it is validated", uploaded > validated)
        assertTrue("the feed is validated before it is generated", validated > generated)
    }

    @Test
    fun `the beta job refuses a tag that already carries the asset`() {
        // A release asset is the rollback path, so it is immutable by contract. Replacing one
        // changes what a version means for everyone who downloads it afterwards.
        assertTrue(
            "nothing checks for an existing asset: ${body("beta")}",
            body("beta").contains("already carries"),
        )
    }

    @Test
    fun `the download URL the feed advertises is the release asset`() {
        // Built from the tag and the staged asset's own name. Anything else and the feed points at a
        // file that may not exist, which a user meets as a failed install with no mention of a feed.
        val generate = body("beta")

        assertTrue(generate, generate.contains("releases/download/\$TAG/\$NAME"))
        assertTrue(generate, generate.contains("-PbetaArchive="))
    }
}
