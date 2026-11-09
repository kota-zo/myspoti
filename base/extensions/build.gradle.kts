// Myspoti extensions: the sandbox third-party scripts run in, their manifest, permissions,
// packages and catalogue. Plain JVM, so an extension runs on a desktop exactly as on the phone
// (the runner is :extensions-cli). It depends on the :api contract only, never on Spotify's
// implementation: a host gives it a MyspotiApi (SpotifyDispatcher) and, if it wants, the raw door.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":api"))
    // The same Rhino NewPipeExtractor brings to the app: no second JavaScript engine.
    implementation("org.mozilla:rhino:1.9.2")
    implementation(libs.serialization.json)
    // The HTTP client of extensions: a custom Dns refuses private addresses at connect time.
    api(libs.okhttp)
    testImplementation("junit:junit:4.13.2")
}
