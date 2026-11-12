// The neutral side of an account provider's hidden web page: the host contract (Android WebView or
// desktop Chrome over CDP), the request/answer bridge to a script injected in the page, and the
// listen sync for a provider whose web player has no remote control (YouTube Music). Plain JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":api"))
    api(libs.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Virtual time for the listen sync's timers.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm:1.11.0")
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}
