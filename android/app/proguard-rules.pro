# Rezvan Mesh ProGuard Rules

# Keep JNI methods
-keepclasseswithmembernames class com.rezvani.mesh.MeshCore {
    native <methods>;
}

# Keep Room entities and DAOs
-keep class com.rezvani.mesh.data.entities.** { *; }
-keep class com.rezvani.mesh.data.dao.** { *; }
-keep class * extends androidx.room.RoomDatabase

# Keep Compose classes
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-dontwarn net.sqlcipher.**

# Keep Tink / Conscrypt — required by security-crypto
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Keep ErrorProne annotations referenced by Tink at runtime
-keep class com.google.errorprone.annotations.** { *; }
-dontwarn com.google.errorprone.annotations.**

# Keep JSR-305 annotations (javax.annotation)
-keep class javax.annotation.** { *; }
-dontwarn javax.annotation.**

-keep class javax.annotation.concurrent.** { *; }
-dontwarn javax.annotation.concurrent.**

# Keep libsodium
-keep class org.libsodium.** { *; }

# Keep BLE classes
-keep class android.bluetooth.** { *; }
-keep class android.bluetooth.le.** { *; }

# Keep WiFi Direct
-keep class android.net.wifi.p2p.** { *; }

# Keep security-crypto
-keep class androidx.security.crypto.** { *; }

# General Android rules
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# Keep Binder for IPC
-keep class com.rezvani.mesh.radio.RezvanRadioService$LocalBinder { *; }

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
