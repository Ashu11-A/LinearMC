// Shared build logic for all linear-core modules.
// Modules are consumed as one unified codebase: legs (Folia/Canvas patches,
// Horizon plugin) import these jars instead of vendoring net/linear sources.
//
// NOTE: type-safe accessors are unavailable in the `subprojects` block of a
// root script, so everything here uses string/FQN configuration.
group = "io.linearmc"
version = "2.0.0-SNAPSHOT"

subprojects {
    apply(plugin = "java-library")

    // Main sources live in <module>/src/ (legacy layout), not src/main/java.
    val sourceSets = extensions.getByName("sourceSets") as org.gradle.api.tasks.SourceSetContainer
    sourceSets.getByName("main").java.srcDir("src")
    sourceSets.getByName("test").java.srcDir("tests")

    configure<org.gradle.api.plugins.JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    }

    repositories {
        mavenCentral()
        // com.mojang:logging is not on Central.
        maven("https://libraries.minecraft.net/")
    }

    dependencies {
        add("testImplementation", "junit:junit:4.13.2")
    }

    tasks.withType<org.gradle.api.tasks.testing.Test> {
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
