import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The custom plugin repository the preview channel is served from.
 *
 * ## What this is for
 *
 * JetBrains Marketplace stays the only stable channel. A prerelease is signed, attached to its
 * GitHub Release, and advertised through a one-file XML feed on GitHub Pages that an IDE can be
 * pointed at under Settings | Plugins | Manage Plugin Repositories. The feed is generated here so
 * that what it advertises is read out of the artifact rather than restated beside it: a feed naming
 * a version, an id or a compatibility range that the packaged plugin does not have is a plugin the
 * IDE downloads and then refuses, with no clue as to which of the two was wrong.
 *
 * ## Where the descriptor actually is
 *
 * Not at the root of the distribution ZIP. A plugin ZIP holds jars under `<name>/lib`, and
 * `META-INF/plugin.xml` is inside **one** of those jars -- measured: of the seven jars in this
 * plugin's own ZIP exactly one carries it, the content-module descriptors being named for their
 * modules rather than `plugin.xml`. So the rule is "the one jar in the archive that has one", and
 * finding none or several is an error rather than a choice, because either means the packaging
 * changed under a feed that would otherwise keep publishing whatever it found first.
 *
 * ## What it refuses
 *
 * Everything it cannot state truthfully:
 *
 * - a plugin id other than the expected one -- a feed is keyed by id, and an IDE offered the wrong
 *   one would install a different plugin over this one;
 * - a version with no prerelease part. The channel contract is that stable ships through
 *   Marketplace, and a stable version reaching this feed would offer users a build the beta channel
 *   was never meant to carry;
 * - a missing `since-build`. Without it an IDE cannot tell whether the build is compatible, so it
 *   either refuses the plugin or installs one that cannot load.
 *
 * `until-build` is **not** required, and that is measured rather than assumed: this plugin's own
 * packaged descriptor is `<idea-version since-build="261"/>` with no upper bound, which is a
 * deliberate open range. It is copied when present and omitted when absent, so the feed says what
 * the artifact says.
 */
object BetaPluginRepo {

    /** The id this repository is allowed to advertise. Read from the artifact and compared. */
    const val EXPECTED_ID: String = "de.wstein.flix-jetbrains-plugin"

    /** What the packaged descriptor says about itself. */
    data class Descriptor(
        val id: String,
        val version: String,
        val sinceBuild: String,
        val untilBuild: String?,
    )

    /**
     * The descriptor packaged inside [zip].
     *
     * @throws IllegalStateException if the archive holds no descriptor, more than one, or one that
     *   does not say what a feed has to state.
     */
    fun descriptorIn(zip: File): Descriptor {
        check(zip.isFile) { "no plugin archive at ${zip.path}" }
        val descriptors = descriptorsIn(zip)
        check(descriptors.isNotEmpty()) {
            "${zip.name} contains no META-INF/plugin.xml in any of its jars, so nothing in it says " +
                "which plugin it is"
        }
        check(descriptors.size == 1) {
            "${zip.name} contains ${descriptors.size} plugin descriptors (${descriptors.keys.sorted()}), " +
                "so which one describes the plugin is a guess"
        }
        return parse(descriptors.values.single(), zip.name)
    }

    /** Every `META-INF/plugin.xml` in the archive, by the jar that holds it. */
    private fun descriptorsIn(zip: File): Map<String, ByteArray> {
        val found = LinkedHashMap<String, ByteArray>()
        ZipInputStream(zip.inputStream().buffered()).use { outer ->
            while (true) {
                val entry = outer.nextEntry ?: break
                if (entry.isDirectory || !entry.name.endsWith(".jar")) {
                    continue
                }
                // Read the jar into memory rather than nesting streams: a ZipInputStream over an
                // entry of another ZipInputStream reads the outer stream's remainder, and the outer
                // iteration then continues from wherever the inner one stopped.
                val jar = outer.readBytes()
                ZipInputStream(ByteArrayInputStream(jar)).use { inner ->
                    while (true) {
                        val nested = inner.nextEntry ?: break
                        if (nested.name == "META-INF/plugin.xml") {
                            found[entry.name] = inner.readBytes()
                        }
                    }
                }
            }
        }
        return found
    }

    private fun parse(xml: ByteArray, source: String): Descriptor {
        val document = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                // The descriptor comes out of an artifact this build produced, but it is still XML
                // arriving from a file: an external entity would be read with the workflow's rights.
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        } catch (malformed: Exception) {
            error("the plugin descriptor in $source is not well-formed XML: ${malformed.message}")
        }

        val id = textOf(document.documentElement, "id")
            ?: error("the plugin descriptor in $source declares no <id>")
        check(id == EXPECTED_ID) {
            "the plugin descriptor in $source declares `$id`, and this repository advertises " +
                "`$EXPECTED_ID`. A feed is keyed by id, so publishing this one would offer an IDE a " +
                "different plugin under the same channel."
        }

        val version = textOf(document.documentElement, "version")
            ?: error("the plugin descriptor in $source declares no <version>")
        check(isPrerelease(version)) {
            "`$version` is not a prerelease version. The preview channel carries prereleases only -- " +
                "stable builds ship through JetBrains Marketplace -- so a version with no `-beta.N` " +
                "suffix reaching this feed means a stable release was published to the wrong channel."
        }

        val ideaVersion = document.documentElement.childElements("idea-version").singleOrNull()
            ?: error(
                "the plugin descriptor in $source declares no <idea-version>, so nothing in it says " +
                    "which IDE builds this plugin works with and an IDE reading the feed cannot tell " +
                    "whether to offer it",
            )
        val since = ideaVersion.getAttribute("since-build").ifBlank {
            error(
                "the <idea-version> in $source has no since-build. An IDE decides whether to offer " +
                    "a plugin by comparing its own build number against that bound; without one it " +
                    "has nothing to compare.",
            )
        }
        val until = ideaVersion.getAttribute("until-build").ifBlank { null }
        return Descriptor(id, version, since, until)
    }

    /**
     * Whether [version] carries a prerelease part, in the sense the channel contract means.
     *
     * A hyphen and something after it: `0.2.0-beta.1` yes, `0.2.0` no. Deliberately not a full
     * semantic-version parse -- the question here is which channel a build belongs to, and the
     * repository is not the place to relitigate what a version number may look like.
     */
    fun isPrerelease(version: String): Boolean {
        val hyphen = version.indexOf('-')
        return hyphen in 1 until version.length - 1
    }

    /** The archive's SHA-256, lowercase hex, as the workflow re-checks after downloading it back. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The feed advertising [descriptor], downloadable from [url].
     *
     * One plugin and one version: the latest compatible beta. Older builds stay reachable as the
     * immutable GitHub Release assets they already are, which is the rollback path, and a feed that
     * listed them all would offer an IDE a choice it resolves by version anyway.
     *
     * The URL must end in the archive's own name. It is the one thing here that cannot be read out
     * of the artifact -- it is where the artifact was *put* -- so it is the one thing that can point
     * somewhere else, and a feed whose link 404s fails at install time with nothing naming the feed.
     */
    fun feed(descriptor: Descriptor, url: String, archiveName: String): String {
        check(url.startsWith("https://")) { "the download URL must be https, and is `$url`" }
        check(url.endsWith("/$archiveName")) {
            "the download URL ends `${url.substringAfterLast('/')}` and the archive is `$archiveName`, " +
                "so the feed would point at something other than the build it describes"
        }
        val ideaVersion = buildString {
            append("""<idea-version since-build="${descriptor.sinceBuild.escaped()}"""")
            descriptor.untilBuild?.let { append(""" until-build="${it.escaped()}"""") }
            append("/>")
        }
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<plugins>
            |  <plugin
            |      id="${descriptor.id.escaped()}"
            |      version="${descriptor.version.escaped()}"
            |      url="${url.escaped()}">
            |    $ideaVersion
            |  </plugin>
            |</plugins>
            |
        """.trimMargin()
    }

    /** A `sha256sum -c` line for [archiveName]. */
    fun checksums(sha256: String, archiveName: String): String = "$sha256  $archiveName\n"

    /**
     * The one distribution archive in [directory].
     *
     * None or several is refused rather than resolved. The build writes a `.tar` beside the `.zip`,
     * and a previous version's archive can survive an incremental build, so "the file that is there"
     * is not the same statement as "the file this build produced".
     */
    fun soleArchive(directory: File, extension: String = ".zip"): File {
        check(directory.isDirectory) { "no distribution directory at ${directory.path}" }
        val archives = directory.listFiles { file -> file.isFile && file.name.endsWith(extension) }
            ?.sortedBy { it.name }
            .orEmpty()
        check(archives.isNotEmpty()) { "no $extension in ${directory.path}" }
        check(archives.size == 1) {
            "${archives.size} archives in ${directory.path} (${archives.map { it.name }}), so which " +
                "one to publish is a guess"
        }
        return archives.single()
    }

    private fun textOf(root: Element, tag: String): String? =
        root.childElements(tag).firstOrNull()?.textContent?.trim()?.ifBlank { null }

    /** Direct children only: a `<version>` inside `<description>` is prose, not the plugin's. */
    private fun Element.childElements(tag: String): List<Element> {
        val children = mutableListOf<Element>()
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element && node.tagName == tag) {
                children += node
            }
        }
        return children
    }

    private fun String.escaped(): String = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
