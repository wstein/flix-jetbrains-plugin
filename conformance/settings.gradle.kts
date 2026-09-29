pluginManagement {
    repositories {
        maven(providers.gradleProperty("flixSpecRepository").getOrElse("https://wstein.github.io/flix-spec/maven"))
    }
}
rootProject.name = "flix-jetbrains-conformance"
