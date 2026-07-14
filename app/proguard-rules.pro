# Vosk — критично для нативной JNI библиотеки
-keep class com.alphacephei.vosk.** { *; }
-keep class org.vosk.** { *; }

# DataStore / Settings — reflection
-keep class com.nous.hermesvoice.data.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**