# LiteRT-LM uses JNI callbacks and Gson reflection.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.gson.** { *; }

# ML Kit finds its components by class name (manifest meta-data) at startup.
# Without these rules R8 strips or renames them and the app crashes on launch.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.firebase.components.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }

# jsoup optional dependency.
-dontwarn com.google.re2j.**

# OkHttp optional platform classes.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
