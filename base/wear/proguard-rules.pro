# Rhino (the extensions' JavaScript engine, run on the watch too): reflection of its engine.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn java.lang.instrument.**
-keepclassmembers @kotlinx.serialization.Serializable class ** { *; }
-dontwarn jdk.dynalink.**
