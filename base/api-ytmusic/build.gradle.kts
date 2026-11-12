// YouTube Music as an account provider: the user's own session (the cookies of their sign-in on the
// official site), the listen sync on the official web app in a hidden page (see :api-web), the
// account's library read and a metadata provider on that session. Plain JVM: the page runs in an
// Android WebView (:host-android) or desktop Chrome over CDP (:host-cdp).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":api-web"))
    implementation(libs.okhttp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm:1.11.0")
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The page script is run under Node (when installed) against a stand-in of the page.
    systemProperty("myspoti.node", providers.gradleProperty("node").getOrElse("node"))
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
