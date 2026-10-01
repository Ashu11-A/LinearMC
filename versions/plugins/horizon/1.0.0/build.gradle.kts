plugins {
    id("java-library")
    // Mixin selectors verified against the pinned Horizon ref (see MIXIN-CONTRACT.md).
    // userdev 2.5.1 + horizon 1.1.0 markers from https://maven.canvasmc.io/public;
    // paperDevBundle takes a BARE version (not a coordinate).
    id("io.canvasmc.weaver.userdev") version "2.5.1"
    id("io.canvasmc.horizon") version "1.1.0"
    // Boot-proof runner only (runServer task below).
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.canvasmc.io/public/")
}

dependencies {
    // Dev bundle pinned to the resolved build (Horizon HEAD catalog floats
    // "26.3.build.+").
    // Our mixins target Mojang-mapped 26.x; defaultRequire:1 in
    // mixins.linearmc.json fails the boot loudly on selector drift.
    paperweight.paperDevBundle("26.3.build.140-beta")
    // Horizon's own mixin artifact (NOT org.spongepowered:mixin:0.8.5).
    implementation("net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7")
    // JiJ-embedded: the server runtime never sees our compile classpath
    // (proven: NoClassDefFoundError without this, genuine .linear with it).
    includeLibrary("com.github.luben:zstd-jni:1.5.6-8")
    // Core modules (composite ../../../../linear-core substitutes these coordinates;
    // includeLibrary => compile classpath + JiJ-embedded like zstd above).
    // Belt-and-braces: server provides all logging; never JiJ-embed slf4j /
    // mojang logging / log4j (log4j-core embedded would hijack server logging).
    includeLibrary("io.linearmc:format:2.0.0-SNAPSHOT") {
        exclude(group = "org.slf4j")
        exclude(group = "com.mojang", module = "logging")
        exclude(group = "org.apache.logging.log4j")
    }
    includeLibrary("io.linearmc:codec:2.0.0-SNAPSHOT") {
        exclude(group = "org.slf4j")
        exclude(group = "com.mojang", module = "logging")
        exclude(group = "org.apache.logging.log4j")
    }
    includeLibrary("io.linearmc:flush:2.0.0-SNAPSHOT") {
        exclude(group = "org.slf4j")
        exclude(group = "com.mojang", module = "logging")
        exclude(group = "org.apache.logging.log4j")
    }
    includeLibrary("io.linearmc:config:2.0.0-SNAPSHOT") {
        exclude(group = "org.slf4j")
        exclude(group = "com.mojang", module = "logging")
        exclude(group = "org.apache.logging.log4j")
    }
    includeLibrary("io.linearmc:command:2.0.0-SNAPSHOT") {
        exclude(group = "org.slf4j")
        exclude(group = "com.mojang", module = "logging")
        exclude(group = "org.apache.logging.log4j")
    }
    includeLibrary("io.linearmc:convert:2.0.0-SNAPSHOT") {
        exclude(group = "org.slf4j")
        exclude(group = "com.mojang", module = "logging")
        exclude(group = "org.apache.logging.log4j")
    }
    includeLibrary("org.lz4:lz4-java:1.8.0")
    compileOnly("org.jspecify:jspecify:1.0.0")
    // Horizon runtime API: exactly one file required (runServer wiring).
    horizon.horizonApi("1.0.0+90")
    // Regression tests for the payload codec (NMS-free; plain JUnit).
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.lz4:lz4-java:1.8.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations.all {
    // codec needs org.lz4:lz4-java (same net.jpountz API as the dev
    // bundle's at.yawk fork); pin the core's copy so JiJ embeds one lz4.
    resolutionStrategy.capabilitiesResolution.withCapability("org.lz4:lz4-java") {
        select("org.lz4:lz4-java:1.8.0")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

horizon {
    splitPluginSourceSets()
    // NO accessTransformerFiles: our only private-target references are
    // @Shadow (resolved at mixin-apply runtime via the binary merge ATs,
    // which still run, plus the wideners entry in horizon.plugin.json).
    // Source ATs via JST failed on CI with exit 1 and an unreachable log;
    // dropping the whole failure class (proven live: genuine .linear boot
    // with zero mixin errors). If a future non-shadow reference needs
    // widening, re-add with a pinned reason.
}

// Horizon splits src/plugin into its own source set, invisible to src/test.
// The Brigadier façade test exercises plugin classes, so wire the plugin
// output into the test compile/runtime classpaths (test-only scope).
// Placed after the horizon block: splitPluginSourceSets() creates the
// "plugin" source set.
sourceSets {
    named("test") {
        compileClasspath += sourceSets["plugin"].output
        runtimeClasspath += sourceSets["plugin"].output
    }
}

tasks.named<xyz.jpenilla.runpaper.task.RunServer>("runServer") {
    minecraftVersion("26.3")
    // Boot-smoke property: LINEAR exercises the dispatch, absence proves
    // ANVIL opt-out. Passes to the SERVER JVM (gradle -D does not propagate).
    jvmArgs("-Dlinearmc.format=LINEAR")
}
