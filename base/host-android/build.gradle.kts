// Android host: runs the Web Player in a WebView and exposes it as a MyspotiApi, plus the
// playback service (media session, notification, Android Auto browsing).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.myspoti.host.android"
    compileSdk = 37

    defaultConfig {
        // Keep the Android host compatible with the app's Android 6.0 minimum.
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":api-spotify"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.brotli)
    implementation(libs.okhttp.zstd)
    implementation(libs.play.services.cronet)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    // The real org.json in unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
