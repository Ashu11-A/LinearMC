// codec: LinearRegionFile, zstd/LZ4 codec, direct streams, envelope codec.
dependencies {
    api(project(":format"))
    api(project(":flush")) // file-level timing/stats forwarding (flush never depends back on codec)
    // Tests exercise the converter round-trip (reverse path reads linear files).
    testImplementation(project(":convert"))
    implementation("org.lz4:lz4-java:1.8.0")
    implementation("com.github.luben:zstd-jni:1.5.6-8")
    implementation("org.slf4j:slf4j-api:2.0.17")
}
