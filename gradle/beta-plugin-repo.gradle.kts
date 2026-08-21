// The opt-in preview channel.
//
// JetBrains Marketplace remains the only stable channel and the default way to install this plugin.
// A prerelease is signed, attached to its GitHub Release as an immutable asset, and advertised by a
// one-file feed on GitHub Pages that a user can add under
// Settings | Plugins | Manage Plugin Repositories. Nothing here publishes to Marketplace, and the
// release workflow keeps the two paths in separate jobs so that neither can reach the other's
// credentials.
//
// The reading of the artifact lives in `buildSrc` (`BetaPluginRepo`), where it is unit-tested
// against real nested ZIPs. This file only wires it to inputs.

val betaArchive = providers.gradleProperty("betaArchive")
val betaDownloadUrl = providers.gradleProperty("betaDownloadUrl")

tasks.register<GenerateBetaPluginRepo>("generateBetaPluginRepo") {
    group = "publishing"
    description = "Writes updatePlugins-beta.xml and SHA256SUMS for the signed prerelease archive."

    // Defaults to the one ZIP the build produced, and refuses if there is more than one. `signPlugin`
    // writes its output beside `buildPlugin`'s, so "the ZIP in the distributions directory" is only
    // an unambiguous statement when exactly one is there -- which is what the workflow arranges, and
    // what `BetaPluginRepo.soleArchive` insists on rather than assumes.
    archive.convention(
        layout.file(
            betaArchive.map { file(it) }.orElse(
                provider { BetaPluginRepo.soleArchive(layout.buildDirectory.dir("distributions").get().asFile) },
            ),
        ),
    )
    downloadUrl.convention(betaDownloadUrl)
    site.convention(layout.buildDirectory.dir("beta-plugin-repo"))
}
