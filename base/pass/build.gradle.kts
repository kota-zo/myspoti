// Passing the app's setup from one of the user's devices to another (a TV, a computer, a second
// phone): the device that receives shows a QR code, the phone scans it and sends its settings,
// extensions and session straight to it over the local network, encrypted to a key only that QR
// code carried. No server in between. Plain JVM: the phone, the TV and the desktop app share it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.serialization.json)
    testImplementation("junit:junit:4.13.2")
}
