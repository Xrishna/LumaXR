# LumaXR ProGuard / R8 Rules
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Kotlinx Serialization
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.lumaxr.player.**$$serializer { *; }
-keepclassmembers class com.lumaxr.player.** {
    *** Companion;
}

# mpv-android-lib JNI callbacks
-keep class is.xyz.mpv.** { *; }
-keepclassmembers class is.xyz.mpv.** { *; }

# Media3 / ExoPlayer & Dolby Codec2
-keep class androidx.media3.** { *; }

# SMBJ & BouncyCastle
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**

# Sardine WebDAV / SimpleXML
-keep class com.thegrizzlylabs.sardineandroid.** { *; }
-keep class org.simpleframework.xml.** { *; }
-dontwarn org.simpleframework.xml.**
-dontwarn javax.xml.stream.**
