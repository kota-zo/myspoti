// Where the app learns about music: several metadata providers behind the :api contract, with what
// keeps them dependable (retries, a cache that outlives failures, a provider skipped while it is down)
// and the matching of one recording between platforms. Plain JVM: no Android, tested on a desktop.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":api"))
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    // The same Rhino as the extensions.
    implementation("org.mozilla:rhino:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
