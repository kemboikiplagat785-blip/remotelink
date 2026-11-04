# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable *; }
-keep,includedescriptorclasses class com.acme.remotesupport.**$$serializer { *; }
-keepclassmembers class com.acme.remotesupport.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Supabase / Ktor
-keep class io.github.jan.supabase.** { *; }
-dontwarn io.ktor.**
-keepclassmembers class io.ktor.** { volatile <fields>; }

# Keep service / accessibility / receiver entry points referenced from the manifest
-keep class com.acme.remotesupport.service.** { *; }
-keep class com.acme.remotesupport.accessibility.** { *; }
