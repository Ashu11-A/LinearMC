// convert: boot/startup + runtime region conversion pipeline.
dependencies {
    api(project(":format"))
    api(project(":codec"))
    // ConverterPumpkinTest builds LZ4 fixtures directly; the codec dependency
    // is implementation-scoped, so the test classpath needs the same coordinate.
    testImplementation("org.lz4:lz4-java:1.8.0")
}
