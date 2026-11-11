// convert: boot/startup + runtime region conversion pipeline.
dependencies {
    api(project(":format"))
    api(project(":codec"))
    implementation("org.slf4j:slf4j-api:2.0.17")
    // ConverterPumpkinTest builds LZ4 fixtures directly; the codec dependency
    // is implementation-scoped, so the test classpath needs the same coordinate.
    testImplementation("org.lz4:lz4-java:1.8.0")
}
