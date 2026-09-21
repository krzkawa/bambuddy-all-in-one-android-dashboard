# OkHttp ships optional references to Conscrypt/BouncyCastle/OpenJSSE.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepattributes SourceFile,LineNumberTable

# Tink, under androidx.security, resolves its key managers reflectively and
# ships protobuf classes R8 cannot see being used. The release APK is the one
# that gets installed, so a stripped key manager would lock the app out of its
# own stored credential.
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-dontwarn javax.annotation.**
