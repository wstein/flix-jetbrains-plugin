import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

/**
 * Writes the preview channel's site: the feed, and the checksum the workflow re-checks with.
 *
 * A task rather than a shell script so that the reading of the artifact is the same code the tests
 * exercise. What it produces is small on purpose -- two files, no index page -- because everything
 * that could be stated twice is instead read out of the ZIP by [BetaPluginRepo].
 *
 * The download URL is the one input that cannot be: it is where the artifact was *put*, which the
 * workflow knows and the build does not.
 */
abstract class GenerateBetaPluginRepo : DefaultTask() {

    @get:InputFile
    abstract val archive: RegularFileProperty

    @get:Input
    @get:Option(option = "download-url", description = "Where the signed ZIP can be downloaded from.")
    abstract val downloadUrl: Property<String>

    @get:OutputDirectory
    abstract val site: DirectoryProperty

    @TaskAction
    fun generate() {
        val zip = archive.get().asFile
        val descriptor = BetaPluginRepo.descriptorIn(zip)
        val sha256 = BetaPluginRepo.sha256(zip)

        val out = site.get().asFile
        out.mkdirs()
        out.resolve("updatePlugins-beta.xml")
            .writeText(BetaPluginRepo.feed(descriptor, downloadUrl.get(), zip.name))
        out.resolve("SHA256SUMS").writeText(BetaPluginRepo.checksums(sha256, zip.name))

        logger.lifecycle("beta feed: ${descriptor.id} ${descriptor.version} (since-build ${descriptor.sinceBuild})")
        logger.lifecycle("beta sha256: $sha256  ${zip.name}")
    }
}
