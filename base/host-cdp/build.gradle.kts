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
    // The neutral hidden page of the other account providers (CdpPageHost).
    api(project(":api-web"))
    implementation(libs.okhttp)
    implementation(libs.serialization.json)

    testImplementation(project(":api-ytmusic"))
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
    // YtmLiveTest: a YouTube Music video id the hidden page plays unheard for a while (see the test).
    systemProperty("myspoti.ytmVideo", providers.gradleProperty("ytmVideo").getOrElse(""))
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
    outputs.upToDateWhen { false }
}
