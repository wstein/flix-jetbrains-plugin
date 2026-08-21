import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What the preview channel's feed may and may not say.
 *
 * Every test below builds a real plugin ZIP -- a ZIP holding a jar holding `META-INF/plugin.xml` --
 * because the nesting is the part most likely to be got wrong and a fixture that skipped it would
 * be asserting about a string.
 *
 * The refusals matter more than the happy path. A feed that publishes a wrong id, a stable version
 * or a missing compatibility range does not fail here; it fails in a user's IDE, some days later,
 * with a message about the plugin rather than about the channel that offered it.
 */
class BetaPluginRepoTest {

    @Rule
    @JvmField
    val folder = TemporaryFolder()

    private fun descriptor(
        id: String = BetaPluginRepo.EXPECTED_ID,
        version: String = "0.2.0-beta.1",
        ideaVersion: String = """<idea-version since-build="261"/>""",
    ): String = """
        <idea-plugin>
          $ideaVersion
          <version>$version</version>
          <id>$id</id>
          <name>Flix</name>
        </idea-plugin>
    """.trimIndent()

    /** A plugin ZIP: `flix/lib/<name>.jar`, each named descriptor inside its own jar. */
    private fun pluginZip(vararg descriptors: Pair<String, String>, name: String = "flix-0.2.0-beta.1.zip"): File {
        val zip = folder.newFile(name)
        ZipOutputStream(zip.outputStream().buffered()).use { outer ->
            descriptors.forEachIndexed { index, (jarName, xml) ->
                val jar = ByteArrayOutputStream()
                ZipOutputStream(jar).use { inner ->
                    inner.putNextEntry(ZipEntry("META-INF/plugin.xml"))
                    inner.write(xml.toByteArray())
                    inner.closeEntry()
                }
                outer.putNextEntry(ZipEntry("flix/lib/$jarName"))
                outer.write(jar.toByteArray())
                outer.closeEntry()
                // A jar with no descriptor beside them, as a real distribution has: searchable
                // options and the content modules. Nothing may mistake one for the plugin.
                outer.putNextEntry(ZipEntry("flix/lib/modules/module-$index.jar"))
                outer.write(ByteArrayOutputStream().also { empty ->
                    ZipOutputStream(empty).use { it.putNextEntry(ZipEntry("META-INF/flix.module.xml")) }
                }.toByteArray())
                outer.closeEntry()
            }
        }
        return zip
    }

    private fun failureOf(block: () -> Unit): String =
        runCatching(block).exceptionOrNull()?.message ?: "no failure at all"

    @Test
    fun `the feed states what the packaged descriptor states`() {
        val zip = pluginZip("flix.jar" to descriptor())

        val read = BetaPluginRepo.descriptorIn(zip)
        val feed = BetaPluginRepo.feed(
            read,
            "https://github.com/wstein/flix-jetbrains-plugin/releases/download/v0.2.0-beta.1/${zip.name}",
            zip.name,
        )

        assertEquals(BetaPluginRepo.EXPECTED_ID, read.id)
        assertEquals("0.2.0-beta.1", read.version)
        assertEquals("261", read.sinceBuild)
        assertEquals(null, read.untilBuild)
        assertTrue(feed, feed.contains("""id="de.wstein.flix-jetbrains-plugin""""))
        assertTrue(feed, feed.contains("""version="0.2.0-beta.1""""))
        assertTrue(feed, feed.contains("""<idea-version since-build="261"/>"""))
    }

    @Test
    fun `an open compatibility range stays open`() {
        // Measured on this plugin's own artifact: the packaged descriptor is
        // `<idea-version since-build="261"/>` with no upper bound. Inventing one would make the feed
        // stop offering the plugin at a build where the plugin itself has no objection.
        val feed = BetaPluginRepo.feed(
            BetaPluginRepo.descriptorIn(pluginZip("flix.jar" to descriptor())),
            "https://example.test/download/flix-0.2.0-beta.1.zip",
            "flix-0.2.0-beta.1.zip",
        )

        assertFalse(feed, feed.contains("until-build"))
    }

    @Test
    fun `a closed compatibility range is carried across`() {
        val zip = pluginZip(
            "flix.jar" to descriptor(ideaVersion = """<idea-version since-build="261" until-build="261.*"/>"""),
        )

        val feed = BetaPluginRepo.feed(
            BetaPluginRepo.descriptorIn(zip),
            "https://example.test/download/${zip.name}",
            zip.name,
        )

        assertTrue(feed, feed.contains("""<idea-version since-build="261" until-build="261.*"/>"""))
    }

    @Test
    fun `a descriptor with no compatibility range is refused`() {
        val zip = pluginZip("flix.jar" to descriptor(ideaVersion = ""))

        val message = failureOf { BetaPluginRepo.descriptorIn(zip) }

        assertTrue(message, message.contains("no <idea-version>"))
    }

    @Test
    fun `a compatibility range with no lower bound is refused`() {
        // An IDE decides whether to offer a plugin by comparing its own build against `since-build`.
        // An empty attribute is not an open range, it is nothing to compare with.
        val zip = pluginZip("flix.jar" to descriptor(ideaVersion = """<idea-version until-build="261.*"/>"""))

        val message = failureOf { BetaPluginRepo.descriptorIn(zip) }

        assertTrue(message, message.contains("no since-build"))
    }

    @Test
    fun `another plugin's id is refused`() {
        // A feed is keyed by id. Advertising the wrong one offers an IDE a different plugin under
        // this channel, which it would install over whatever holds that id already.
        val zip = pluginZip("flix.jar" to descriptor(id = "dev.wstein.flix-jetbrains-plugin"))

        val message = failureOf { BetaPluginRepo.descriptorIn(zip) }

        assertTrue(message, message.contains("dev.wstein.flix-jetbrains-plugin"))
        assertTrue(message, message.contains(BetaPluginRepo.EXPECTED_ID))
    }

    @Test
    fun `a stable version is refused, because stable is Marketplace's channel`() {
        val zip = pluginZip("flix.jar" to descriptor(version = "0.2.0"))

        val message = failureOf { BetaPluginRepo.descriptorIn(zip) }

        assertTrue(message, message.contains("not a prerelease version"))
    }

    @Test
    fun `what counts as a prerelease`() {
        assertTrue(BetaPluginRepo.isPrerelease("0.2.0-beta.1"))
        assertTrue(BetaPluginRepo.isPrerelease("1.0.0-rc.2"))
        assertFalse(BetaPluginRepo.isPrerelease("0.2.0"))
        // A trailing or leading hyphen names no prerelease, and neither does an empty version.
        assertFalse(BetaPluginRepo.isPrerelease("0.2.0-"))
        assertFalse(BetaPluginRepo.isPrerelease("-beta.1"))
        assertFalse(BetaPluginRepo.isPrerelease(""))
    }

    @Test
    fun `a malformed descriptor is refused as malformed`() {
        // Not as "no id". The distinction is what a maintainer reads at three in the morning.
        val zip = pluginZip("flix.jar" to "<idea-plugin><id>de.wstein.flix-jetbrains-plugin</name>")

        val message = failureOf { BetaPluginRepo.descriptorIn(zip) }

        assertTrue(message, message.contains("not well-formed XML"))
    }

    @Test
    fun `an archive with two descriptors is refused rather than resolved`() {
        // Which one describes the plugin would be a guess, and the guess would be silent.
        val zip = pluginZip("flix.jar" to descriptor(), "other.jar" to descriptor(version = "9.9.9-beta.1"))

        val message = failureOf { BetaPluginRepo.descriptorIn(zip) }

        assertTrue(message, message.contains("2 plugin descriptors"))
    }

    @Test
    fun `an archive with no descriptor is refused`() {
        val zip = pluginZip()

        val message = failureOf { BetaPluginRepo.descriptorIn(zip) }

        assertTrue(message, message.contains("no META-INF/plugin.xml"))
    }

    @Test
    fun `a feed link that names another file is refused`() {
        // The URL is the one thing in the feed that cannot be read out of the artifact -- it is
        // where the artifact was put -- so it is the one thing that can point somewhere else. The
        // failure it prevents happens at install time and names the plugin, never the feed.
        val zip = pluginZip("flix.jar" to descriptor())
        val read = BetaPluginRepo.descriptorIn(zip)

        val message = failureOf {
            BetaPluginRepo.feed(read, "https://example.test/download/flix-0.1.0.zip", zip.name)
        }

        assertTrue(message, message.contains("point at something other than the build it describes"))
    }

    @Test
    fun `a feed link that is not https is refused`() {
        val zip = pluginZip("flix.jar" to descriptor())

        val message = failureOf {
            BetaPluginRepo.feed(BetaPluginRepo.descriptorIn(zip), "http://example.test/${zip.name}", zip.name)
        }

        assertTrue(message, message.contains("must be https"))
    }

    @Test
    fun `the checksum is the archive's own`() {
        val zip = pluginZip("flix.jar" to descriptor())

        val sha = BetaPluginRepo.sha256(zip)

        assertEquals(64, sha.length)
        assertEquals(sha, BetaPluginRepo.sha256(zip))
        assertEquals("$sha  ${zip.name}\n", BetaPluginRepo.checksums(sha, zip.name))
    }

    @Test
    fun `two archives in one directory are refused rather than picked between`() {
        // The build writes a `.tar` beside the `.zip`, and an earlier version's archive survives an
        // incremental build. "The file that is there" is not "the file this build produced".
        val directory = folder.newFolder("distributions")
        File(directory, "flix-0.2.0-beta.1.zip").writeText("a")
        File(directory, "flix-0.1.0.zip").writeText("b")

        val message = failureOf { BetaPluginRepo.soleArchive(directory) }

        assertTrue(message, message.contains("2 archives"))
    }

    @Test
    fun `an empty distribution directory is refused`() {
        val directory = folder.newFolder("empty")
        File(directory, "flix-0.2.0-beta.1.tar").writeText("not a zip")

        val message = failureOf { BetaPluginRepo.soleArchive(directory) }

        assertTrue(message, message.contains("no .zip"))
    }

    @Test
    fun `the sole archive is found beside the tar the build also writes`() {
        val directory = folder.newFolder("one")
        File(directory, "flix-0.2.0-beta.1.zip").writeText("a")
        File(directory, "flix-0.2.0-beta.1.tar").writeText("b")

        assertEquals("flix-0.2.0-beta.1.zip", BetaPluginRepo.soleArchive(directory).name)
    }

    @Test
    fun `a value needing escaping does not break the feed`() {
        // Nothing in a version or a build number ought to need it. The feed is XML, though, and a
        // generator that writes XML by concatenation and never escapes is one odd character away
        // from a file no IDE can parse.
        //
        // Written escaped in the fixture because the descriptor is itself XML: a raw `&` there is a
        // malformed descriptor, which is a different refusal and already has a test of its own.
        val zip = pluginZip("flix.jar" to descriptor(version = "0.2.0-beta.1&quot;&amp;&lt;"))

        val feed = BetaPluginRepo.feed(
            BetaPluginRepo.descriptorIn(zip),
            "https://example.test/download/${zip.name}",
            zip.name,
        )

        assertTrue(feed, feed.contains("""version="0.2.0-beta.1&quot;&amp;&lt;""""))
    }
}
