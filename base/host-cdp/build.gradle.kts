// Desktop host: drives the Web Player in a local Google Chrome over the DevTools protocol.
// Used by the integration tests, so the whole API runs on a PC without an emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":api-spotify"))
    implementation(libs.okhttp)
    implementation(libs.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Live tests need Chrome with --remote-debugging-port and a signed-in Web Player.
    systemProperty("myspoti.cdp", providers.gradleProperty("cdp").getOrElse(""))
    // JamLiveTest: a Jam to join (its token) and a command that prints the host's playback (see the test).
    systemProperty("myspoti.jam", providers.gradleProperty("jam").getOrElse(""))
    systemProperty("myspoti.jamHost", providers.gradleProperty("jamHost").getOrElse(""))
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
    outputs.upToDateWhen { false }
}
