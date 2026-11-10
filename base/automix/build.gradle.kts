// AutoMix: song analysis (beats, bars, phrases, key, loudness) and transition planning.
// Pure Kotlin, no Android: the phone and the desktop bench (tools/automix) run the same code.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Beat This runs on ONNX Runtime: the app brings onnxruntime-android, the desktop bench the JVM build
    // (same Java API, same package).
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    testImplementation("junit:junit:4.13.2")
}

// Desktop bench on real songs: ./gradlew :automix:bench -Pargs="<dir> ..." (see tools/automix/README.md).
tasks.register<JavaExec>("bench") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.myspoti.automix.BenchKt")
    args = (findProperty("args") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
    jvmArgs("-Xmx4g")
    environment("AUTOMIX_THREADS", (findProperty("threads") as String?) ?: "2")
    environment("AUTOMIX_STRETCH", (findProperty("stretch") as String?) ?: "0.12")
    environment("AUTOMIX_MODEL", rootProject.file("app/src/main/assets/automix/beat-this-small0-int8.onnx").path)
}
