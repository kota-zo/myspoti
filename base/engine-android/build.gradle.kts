// Android side of the native playback engine (:engine): the platform seams on Android and Wear OS.
// Http over Chromium's stack (the platform's HttpEngine on Android 14+, Cronet from Play services
// before), the dealer WebSocket over OkHttp, and a Widevine DrmPlayer on Media3 ExoPlayer.
// Nothing here knows the WebView, the phone UI or :host-android, so a Wear OS app can use it as is;
// tokens come from whoever builds the engine (TokenSource). See docs/api/NATIVE-PLAYBACK.md.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.myspoti.engine.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }
    testOptions {
        unitTests { isIncludeAndroidResources = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":engine"))
    // Already shipped by :app (1.8.0): only the player itself; extractor, datasource and decoder
    // come with it. No DASH, HLS or Cronet data source: the CDN is read by SegmentDataSource.
    implementation(libs.media3.exoplayer)
    implementation(libs.okhttp)
    implementation(libs.play.services.cronet)
    implementation(libs.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
