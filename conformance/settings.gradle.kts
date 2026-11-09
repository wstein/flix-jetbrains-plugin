pluginManagement {
    repositories {
        ivy {
            name = "qualifiedFlixSpec079"
            url = uri("https://wstein.github.io/flix-spec/maven/io/github/wstein/")
            patternLayout { artifact("[artifact]/0.79.0-SNAPSHOT/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
        }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.github.wstein.flix-spec") {
                useModule("io.github.wstein:flix-spec-gradle-plugin:${requested.version}")
            }
        }
    }
}
rootProject.name = "flix-jetbrains-conformance"
