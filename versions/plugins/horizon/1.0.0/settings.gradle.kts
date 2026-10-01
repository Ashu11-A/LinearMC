pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = "Canvas"
            url = uri("https://maven.canvasmc.io/public")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "horizon-plugin"

includeBuild("../../../../linear-core") {
    // linear-core sets group/version on the ROOT project only, so automatic
    // composite substitution cannot match; pin the mapping explicitly.
    dependencySubstitution {
        substitute(module("io.linearmc:format:2.0.0-SNAPSHOT")).using(project(":format"))
        substitute(module("io.linearmc:codec:2.0.0-SNAPSHOT")).using(project(":codec"))
        substitute(module("io.linearmc:flush:2.0.0-SNAPSHOT")).using(project(":flush"))
        substitute(module("io.linearmc:config:2.0.0-SNAPSHOT")).using(project(":config"))
        substitute(module("io.linearmc:command:2.0.0-SNAPSHOT")).using(project(":command"))
        substitute(module("io.linearmc:convert:2.0.0-SNAPSHOT")).using(project(":convert"))
    }
}
