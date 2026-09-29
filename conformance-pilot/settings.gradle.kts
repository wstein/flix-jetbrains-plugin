pluginManagement {
    repositories {
        maven(providers.gradleProperty("flixSpec.pilotRepository").get())
    }
}
rootProject.name = "flix-jetbrains-conformance-pilot"
