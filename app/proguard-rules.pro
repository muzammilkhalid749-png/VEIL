# ==============================================================================
# VEIL PRODUCTION R8 / PROGUARD RULES (ZERO-TRUST HARDENED RELEASE)
# ==============================================================================
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# 1. VEIL Application Models, Controllers & Custom Canvas Views
-keep class com.m3.veil.model.** { *; }
-keepclassmembers class com.m3.veil.model.** { *; }
-keep class com.m3.veil.database.EncryptedChatDatabase$* { *; }
-keep class com.m3.veil.manager.VeilBoardController$* { *; }
-keep class com.m3.veil.manager.** { *; }
-keep class com.m3.veil.utils.SovereignIdentityAndDirectoryEngine$* { *; }
-keep class com.m3.veil.webrtc.SovereignNatTraversalEngine$* { *; }
-keep class com.m3.veil.webrtc.VeilPacket { *; }
-keep class com.m3.veil.calling.** { *; }
-keep class com.m3.veil.services.** { *; }
-keep class com.m3.veil.ui.views.** {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    *;
}

# 2. SQLCipher & Zetetic Native JNI Callbacks
-keep class net.zetetic.database.** { *; }
-keepclassmembers class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }
-keepclassmembers class net.sqlcipher.** { *; }
-dontwarn net.zetetic.database.**
-dontwarn net.sqlcipher.**

# 3. WebRTC Native JNI & PeerConnection / DataChannel / VideoFrame Callbacks
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-dontwarn org.webrtc.**

# 4. Socket.io, Engine.io & OkHttp Signaling Transport
-keep class io.socket.** { *; }
-keep class io.engineio.** { *; }
-dontwarn io.socket.**
-dontwarn io.engineio.**
-dontwarn okhttp3.**
-dontwarn okio.**

# 5. Google Crypto Tink, BouncyCastle & AndroidX Security Keystore
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
