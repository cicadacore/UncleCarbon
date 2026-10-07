# Code & Line Number Preservations
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Native JNI Bridge Protection (MTE & Memory Zeroing)
-keep class com.hamoon.unclecarbon.util.NativeSecurityBridge {
    native <methods>;
    *;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# Android Keystore / StrongBox providers
-keep class android.security.keystore.** { *; }
-keep class androidx.security.crypto.** { *; }
# Compile-time-only annotations referenced by Tink (pulled in by security-crypto)
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy

# JavaMail and Activation
-keep class javax.mail.** { *; }
-keep class javax.mail.internet.** { *; }
-keep class javax.activation.** { *; }
-keep class java.beans.** { *; }
-dontwarn java.beans.**
-dontwarn javax.activation.**
-keep class com.sun.mail.** { *; }
-keep class com.sun.mail.smtp.** { *; }
-keep class com.sun.mail.handlers.** { *; }
-dontwarn com.sun.mail.**
# META-INF/javamail.* and META-INF/mailcap* are Java resources; R8 keeps them as-is.

# Manifest-registered receivers
-keep class com.hamoon.unclecarbon.receivers.** { *; }