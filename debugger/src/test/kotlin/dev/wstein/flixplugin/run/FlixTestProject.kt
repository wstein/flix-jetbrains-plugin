package dev.wstein.flixplugin.run

import dev.wstein.flixplugin.FlixBuildSpec
import java.nio.file.Files
import java.nio.file.Path

/**
 * A project directory holding a build manifest, as `BuildManifest.write` produces one.
 *
 * Written as text rather than stubbed behind an interface so that the parser in `FlixBuildSpec` is
 * exercised by every test that reaches for a launch. The manifest is a file format shared with
 * another repository; a test that mocked it past the parse would keep passing after the format
 * changed, which is the one failure this fixture is here to make impossible.
 */
internal object FlixTestProject {

    /** A temporary project whose development build recorded [mainClass] (or no entry point). */
    fun withManifest(mainClass: String? = "Main", java: String = "/opt/jdk/bin/java"): Path {
        val root = Files.createTempDirectory("flix-project")
        val manifest = FlixBuildSpec.manifestIn(root)
        Files.createDirectories(manifest.parent)
        val main = mainClass?.let { """"mainClass":"$it",""" }.orEmpty()
        Files.writeString(
            manifest,
            """
            {
              "formatVersion":4,
              "compilerVersion":"0.75.2+fork",
              "fingerprint":"f",
              "frontendFingerprint":"ff",
              "products":["Main.class"],
              "sources":["src/Main.flix"],
              "sourcesDigest":"d",
              "hasMain":${mainClass != null},
              "launch":{
                "java":"$java",
                $main"runtimeClasspath":["/p/build/development/class","/p/lib/external/core.jar"]
              }
            }
            """.trimIndent(),
        )
        return root
    }
}
