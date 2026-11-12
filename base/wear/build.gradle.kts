// The Wear OS app: the native engine and its Android player on the watch (no WebView there), with
// the account's session and the list of playlists asked to the phone's app over the Wearable Data
// Layer. Same application id and signature as the phone's app: the Data Layer only links those.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.myspoti.wear"
    compileSdk = 37

    val version = (findProperty("appVersionName") as String?) ?: "1.0.0"
    val beta = findProperty("appVersionName") == null || version.contains("-beta")
    defaultConfig {
        // As the phone's app (base/app): betas "app.orpheon.beta", releases "app.orpheon", debug builds ".dev".
        applicationId = if (beta) "app.orpheon.beta" else "app.orpheon"
        minSdk = 30
        targetSdk = 36
        versionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 200
        versionName = version
    }
    buildTypes {
        debug { applicationIdSuffix = ".dev" }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The phone's release key in CI (the two apps must be signed alike); the debug key locally.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("roborazzi.test.record", "true") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":engine-android"))
    // The listen mirror on the watch: the sync's rules on the native engine (EngineSyncPage).
    implementation(project(":api-spotify"))
    // The phone's extensions run on the watch too, and the Wi-Fi link to the phone.
    implementation(project(":extensions"))
    implementation(project(":pass"))
    // A list played from the extensions: a plain player (DASH for the manifests some give).
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.8.0")
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation("androidx.wear.compose:compose-foundation:1.7.1")
    // Wear's Material 3 (scaffold, pager, lists, time text) and Google's Horologist media UI on it
    // (Apache-2.0): the player's controls, progress ring, artwork background and volume.
    implementation("androidx.wear.compose:compose-material3:1.7.1")
    implementation("com.google.android.horologist:horologist-media-ui-material3:0.8.4-alpha")
    implementation("com.google.android.horologist:horologist-audio-ui-material3:0.8.4-alpha")
    implementation("com.google.android.horologist:horologist-compose-layout:0.8.4-alpha")
    implementation("com.google.android.horologist:horologist-images-coil:0.8.4-alpha")
    // Refresh only; the two other icons are drawn in WatchIcons (the extended set is ~60 MB).
    implementation("androidx.compose.material:material-icons-core:1.7.8")

    // Screenshots of the watch screen on the computer (WatchScreenshotTest): Robolectric draws them, Roborazzi saves them.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.1")
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
