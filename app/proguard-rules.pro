# LumaXR ProGuard / R8 Rules
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlinx Serialization (classes, objects, enums)
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.lumaxr.player.**$$serializer { *; }
-keepclassmembers class com.lumaxr.player.** {
    *** Companion;
}
-keepclassmembers @kotlinx.serialization.Serializable class com.lumaxr.player.** {
    public static final *** INSTANCE;
    public *** serializer();
}

# mpv-android-lib JNI callbacks
-keep class is.xyz.mpv.** { *; }

# SMBJ & BouncyCastle
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn net.engio.mbassy.**

# Sardine WebDAV / SimpleXML
-keep class com.thegrizzlylabs.sardineandroid.** { *; }
-keep class org.simpleframework.xml.** { *; }
-dontwarn org.simpleframework.xml.**
-dontwarn javax.xml.stream.**
