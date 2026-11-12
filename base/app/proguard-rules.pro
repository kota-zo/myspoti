# The Web Player page calls the host through this @JavascriptInterface on WebViews without
# web message listeners (PlayerChannel.kt).
-keepclassmembers class app.myspoti.host.android.PlayerChannel {
    @android.webkit.JavascriptInterface <methods>;
}
# The extension page's bridge (ExtensionPage.kt): the page's script calls it by method name.
-keepclassmembers class com.music.spotui.ui.screens.PageBridge {
    @android.webkit.JavascriptInterface <methods>;
}
# Bundled resources read by name (bridge script, GraphQL operation table).
-keep class app.myspoti.spotify.bridge.BridgeScript { *; }
-keep class app.myspoti.spotify.gql.GqlOps { *; }
-dontwarn javax.annotation.concurrent.GuardedBy
-keepclassmembers enum * { *; }
-keepclassmembers class * implements java.io.Serializable { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepattributes *Annotation*,JavascriptInterface,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.google.protos.** { *; }
-keep class ** extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class ** extends com.google.protobuf.GeneratedMessage { *; }
-keepclassmembers class ** extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-keepclassmembers class ** extends com.google.protobuf.GeneratedMessage {
    <fields>;
}

# NewPipe / Rhino - java.beans.* not available on Android on device
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn javax.script.**
# do not add -keep class org.mozilla.javascript.** - it pulls in classes
# referencing java.beans.* which breaks ART install-time verification

-dontwarn java.lang.instrument.**

-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class androidx.work.** {
    <init>(...);
}

# Native UI (Spotui port) + Spotify data layer
-keep class com.metrolist.spotify.models.** { *; }
-keep class com.music.spotui.data.entity.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** { *; }
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**

# NewPipeExtractor (YouTube music videos): reflection / Rhino JS engine, as in NewPipe.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn org.jsoup.**
-dontwarn com.google.re2j.**
-dontwarn jdk.dynalink.**

# ML Kit (the Jam QR scanner) creates its component registrars by reflection: without their
# no-argument constructors it fails to start and the scan crashes.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.** { *; }

# Extensions: a Spotify call's result is turned into JSON from the API models' own field names
# (SpotifyDispatcher reads them by reflection), and the sandbox loads prelude.js from its jar.
-keepclassmembers class app.myspoti.api.** { <fields>; }
-keep class app.myspoti.extensions.** { *; }
# The metadata catalog loads its scripts (metadata/*.js) from its jar, by the package of its classes.
-keep class app.myspoti.catalog.ScriptProvider
-keep class app.myspoti.catalog.ScriptEngine

# The relay's Cronet (before Android 14): CronetProvider finds the Play services provider by its
# class name, and zstd's decoder is reached from its native library by JNI.
-keep class com.google.android.gms.net.PlayServicesCronetProvider { *; }
-keep class com.google.android.gms.net.GmsCoreCronetProvider { *; }
-keep class com.squareup.zstd.** { *; }

# ONNX Runtime (AutoMix's beat model): its native library finds its Java classes, constructors and
# fields by name over JNI (OnnxTensor, TensorInfo, OnnxJavaType, OrtException...). Renamed by R8,
# the first analysis aborted the process (native crash, dev 3135, the first minified build with it).
-keep class ai.onnxruntime.** { *; }
